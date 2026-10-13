//! Video encoding for screen view on Windows, with Media Foundation encoders (they come with Windows or the
//! graphics driver, so the codec licences come with them):
//!   - H.265 (HEVC): the graphics card's hardware encoder (NVIDIA, Intel, AMD; asynchronous MFTs), else
//!     Microsoft's software one from "HEVC Video Extensions" if installed. About a third less data than H.264.
//!   - H.264: Microsoft's software encoder, always present.
//! Input is the captured BGRA screen, converted here to NV12 (BT.601, limited range: the colors every decoder
//! assumes when the stream doesn't say); output is an Annex-B byte stream, with the parameter sets
//! (H.264 SPS/PPS, H.265 VPS/SPS/PPS) in front of every keyframe.
//!
//! Exposed as `airvideo_*` (C ABI, used by the desktop app's MediaSession). One encoder per thread.

use std::mem::ManuallyDrop;

use windows::core::Interface;
use windows::Win32::Foundation::{VARIANT_FALSE, VARIANT_TRUE};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, COINIT_MULTITHREADED};
use windows::Win32::System::Variant::{VARIANT, VARIANT_0, VARIANT_0_0, VARIANT_0_0_0, VT_BOOL, VT_UI4};

/// ICodecAPI values: VT_BOOL / VT_UI4 variants.
fn v_bool(b: bool) -> VARIANT {
    variant(VT_BOOL, VARIANT_0_0_0 { boolVal: if b { VARIANT_TRUE } else { VARIANT_FALSE } })
}

fn v_u32(v: u32) -> VARIANT {
    variant(VT_UI4, VARIANT_0_0_0 { ulVal: v })
}

fn variant(vt: windows::Win32::System::Variant::VARENUM, value: VARIANT_0_0_0) -> VARIANT {
    VARIANT {
        Anonymous: VARIANT_0 {
            Anonymous: ManuallyDrop::new(VARIANT_0_0 { vt, wReserved1: 0, wReserved2: 0, wReserved3: 0, Anonymous: value }),
        },
    }
}

/// The AirPointer protocol's number for H.265 (Protocol.CodecH265); anything else means H.264 (2).
pub const CODEC_H265: u32 = 4;

pub struct Encoder {
    mft: IMFTransform,
    activate: IMFActivate,
    codec: Option<ICodecAPI>,
    hevc: bool,
    /// Asynchronous (hardware) encoder: driven by its events; `need_input` counts unanswered requests.
    events: Option<IMFMediaEventGenerator>,
    need_input: u32,
    width: u32,
    height: u32,
    fps: u32,
    nv12: Vec<u8>,
    out: Vec<u8>,
    frame: i64,
    sequence_header: Vec<u8>,
}

fn com_init() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        let _ = MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    }
}

fn pack(hi: u32, lo: u32) -> u64 {
    ((hi as u64) << 32) | lo as u64
}

impl Encoder {
    /// `width`/`height` must be even. `bitrate` in bits per second. `codec`: CODEC_H264 or CODEC_H265.
    pub fn new(width: u32, height: u32, fps: u32, bitrate: u32, codec: u32) -> windows::core::Result<Self> {
        com_init();
        let hevc = codec == CODEC_H265;
        let subtype = if hevc { MFVideoFormat_HEVC } else { MFVideoFormat_H264 };
        // H.265: hardware first (fast, and usually the only H.265 encoder), then software. H.264: Microsoft's
        // synchronous software encoder, as before (simple and always present).
        let kinds: &[MFT_ENUM_FLAG] = if hevc {
            &[MFT_ENUM_FLAG_HARDWARE, MFT_ENUM_FLAG_SYNCMFT]
        } else {
            &[MFT_ENUM_FLAG_SYNCMFT]
        };
        let mut last = windows::core::Error::from(MF_E_TOPO_CODEC_NOT_FOUND);
        for &kind in kinds {
            for activate in unsafe { encoders(subtype, kind)? } {
                match unsafe { Self::open(&activate, kind == MFT_ENUM_FLAG_HARDWARE, hevc, width, height, fps, bitrate) } {
                    Ok(e) => return Ok(e),
                    Err(e) => {
                        unsafe {
                            let _ = activate.ShutdownObject();
                        }
                        last = e;
                    }
                }
            }
        }
        Err(last)
    }

    unsafe fn open(
        activate: &IMFActivate, hardware: bool, hevc: bool, width: u32, height: u32, fps: u32, bitrate: u32,
    ) -> windows::core::Result<Self> {
        let mft: IMFTransform = activate.ActivateObject()?;
        let events = if hardware {
            // Asynchronous MFTs must be unlocked before use, and are driven by their event queue.
            mft.GetAttributes()?.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1)?;
            Some(mft.cast::<IMFMediaEventGenerator>()?)
        } else {
            None
        };
        let subtype = if hevc { MFVideoFormat_HEVC } else { MFVideoFormat_H264 };

        let codec: Option<ICodecAPI> = mft.cast().ok();
        if let Some(c) = &codec {
            // Before the media types: low latency (no frame reordering / lookahead), no B-frames.
            let _ = c.SetValue(&CODECAPI_AVLowLatencyMode, &v_bool(true));
            let _ = c.SetValue(&CODECAPI_AVEncMPVDefaultBPictureCount, &v_u32(0u32));
            let _ = c.SetValue(&CODECAPI_AVEncCommonRateControlMode, &v_u32(eAVEncCommonRateControlMode_CBR.0 as u32));
            let _ = c.SetValue(&CODECAPI_AVEncCommonMeanBitRate, &v_u32(bitrate));
            let _ = c.SetValue(&CODECAPI_AVEncMPVGOPSize, &v_u32(fps * 4));
        }

        let out_type = MFCreateMediaType()?;
        out_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        out_type.SetGUID(&MF_MT_SUBTYPE, &subtype)?;
        out_type.SetUINT32(&MF_MT_AVG_BITRATE, bitrate)?;
        out_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(width, height))?;
        out_type.SetUINT64(&MF_MT_FRAME_RATE, pack(fps, 1))?;
        out_type.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, pack(1, 1))?;
        out_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        let profile = if hevc { eAVEncH265VProfile_Main_420_8.0 as u32 } else { eAVEncH264VProfile_Main.0 as u32 };
        out_type.SetUINT32(&MF_MT_MPEG2_PROFILE, profile)?;
        out_type.SetUINT32(&MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT601.0 as u32)?;
        out_type.SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235.0 as u32)?;
        mft.SetOutputType(0, &out_type, 0)?;

        let in_type = MFCreateMediaType()?;
        in_type.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        in_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
        in_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(width, height))?;
        in_type.SetUINT64(&MF_MT_FRAME_RATE, pack(fps, 1))?;
        in_type.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, pack(1, 1))?;
        in_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        in_type.SetUINT32(&MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT601.0 as u32)?;
        in_type.SetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235.0 as u32)?;
        mft.SetInputType(0, &in_type, 0)?;

        // Parameter sets, in case the encoder doesn't repeat them in front of keyframes.
        let mut sequence_header = Vec::new();
        if let Ok(t) = mft.GetOutputCurrentType(0) {
            let mut size = 0u32;
            if t.GetBlobSize(&MF_MT_MPEG_SEQUENCE_HEADER).map(|s| size = s).is_ok() && size > 0 {
                sequence_header.resize(size as usize, 0);
                if t.GetBlob(&MF_MT_MPEG_SEQUENCE_HEADER, &mut sequence_header, None).is_err() {
                    sequence_header.clear();
                }
            }
        }

        mft.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
        mft.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
        Ok(Self {
            mft,
            activate: activate.clone(),
            codec,
            hevc,
            events,
            need_input: 0,
            width,
            height,
            fps: fps.max(1),
            nv12: vec![0; (width * height * 3 / 2) as usize],
            out: Vec::with_capacity(1 << 20),
            frame: 0,
            sequence_header,
        })
    }

    /// Encodes one BGRA frame (at least width×height, rows `stride` bytes apart). Returns the encoded bytes
    /// (empty if the encoder is still buffering) and whether they contain a keyframe.
    pub fn encode(&mut self, bgra: &[u8], stride: usize, keyframe: bool) -> windows::core::Result<(&[u8], bool)> {
        bgra_to_nv12(bgra, stride, self.width as usize, self.height as usize, &mut self.nv12);
        unsafe {
            if keyframe {
                if let Some(c) = &self.codec {
                    let _ = c.SetValue(&CODECAPI_AVEncVideoForceKeyFrame, &v_u32(1u32));
                }
            }
            let buffer = MFCreateMemoryBuffer(self.nv12.len() as u32)?;
            let mut ptr = std::ptr::null_mut();
            buffer.Lock(&mut ptr, None, None)?;
            std::ptr::copy_nonoverlapping(self.nv12.as_ptr(), ptr, self.nv12.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(self.nv12.len() as u32)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            let duration = 10_000_000 / self.fps as i64; // 100 ns units
            sample.SetSampleTime(self.frame * duration)?;
            sample.SetSampleDuration(duration)?;
            self.frame += 1;

            self.out.clear();
            let mut key = false;
            if self.events.is_some() {
                self.encode_async(&sample, &mut key)?;
            } else {
                self.mft.ProcessInput(0, &sample, 0)?;
                while self.take_output(&mut key)? {}
            }
            if key && !self.sequence_header.is_empty() && !has_parameter_sets(&self.out, self.hevc) {
                let mut with = self.sequence_header.clone();
                with.extend_from_slice(&self.out);
                self.out = with;
            }
            Ok((&self.out, key))
        }
    }

    /// Hardware encoder: waits for it to ask for input, hands over the frame, then waits (briefly) for the
    /// encoded frame, so a still screen's last picture isn't left inside the encoder.
    unsafe fn encode_async(&mut self, sample: &IMFSample, key: &mut bool) -> windows::core::Result<()> {
        let start = std::time::Instant::now();
        while self.need_input == 0 {
            if start.elapsed().as_millis() > 1000 {
                return Err(windows::core::Error::new(MF_E_TRANSFORM_NEED_MORE_INPUT, "the hardware encoder stopped asking for frames"));
            }
            if !self.pump(key)? {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }
        self.need_input -= 1;
        self.mft.ProcessInput(0, sample, 0)?;
        let fed = std::time::Instant::now();
        while self.out.is_empty() && fed.elapsed().as_millis() < 100 {
            if !self.pump(key)? {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }
        Ok(())
    }

    /// Handles one pending encoder event, if any. Returns false when there was none.
    unsafe fn pump(&mut self, key: &mut bool) -> windows::core::Result<bool> {
        let events = self.events.as_ref().unwrap();
        let event = match events.GetEvent(MF_EVENT_FLAG_NO_WAIT) {
            Ok(e) => e,
            Err(e) if e.code() == MF_E_NO_EVENTS_AVAILABLE => return Ok(false),
            Err(e) => return Err(e),
        };
        let kind = event.GetType()? as i32;
        if kind == METransformNeedInput.0 {
            self.need_input += 1;
        } else if kind == METransformHaveOutput.0 {
            self.take_output(key)?;
        }
        Ok(true)
    }

    /// Takes one encoded output from the encoder into `out`. Returns false when it has none ready.
    unsafe fn take_output(&mut self, key: &mut bool) -> windows::core::Result<bool> {
        let info = self.mft.GetOutputStreamInfo(0)?;
        let provides = (info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32) != 0;
        let own = if provides {
            None
        } else {
            let s = MFCreateSample()?;
            s.AddBuffer(&MFCreateMemoryBuffer(info.cbSize.max(1 << 20))?)?;
            Some(s)
        };
        let mut data = [MFT_OUTPUT_DATA_BUFFER {
            dwStreamID: 0,
            pSample: ManuallyDrop::new(own),
            dwStatus: 0,
            pEvents: ManuallyDrop::new(None),
        }];
        let mut status = 0u32;
        let r = self.mft.ProcessOutput(0, &mut data, &mut status);
        let sample = ManuallyDrop::into_inner(std::mem::replace(&mut data[0].pSample, ManuallyDrop::new(None)));
        drop(ManuallyDrop::into_inner(std::mem::replace(&mut data[0].pEvents, ManuallyDrop::new(None))));
        match r {
            Ok(()) => {
                if let Some(s) = sample {
                    if s.GetUINT32(&MFSampleExtension_CleanPoint).unwrap_or(0) != 0 {
                        *key = true;
                    }
                    let b = s.ConvertToContiguousBuffer()?;
                    let mut p = std::ptr::null_mut();
                    let mut len = 0u32;
                    b.Lock(&mut p, None, Some(&mut len))?;
                    self.out.extend_from_slice(std::slice::from_raw_parts(p, len as usize));
                    b.Unlock()?;
                }
                Ok(true)
            }
            Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => Ok(false),
            Err(e) if e.code() == MF_E_TRANSFORM_STREAM_CHANGE => {
                let t = self.mft.GetOutputAvailableType(0, 0)?;
                self.mft.SetOutputType(0, &t, 0)?;
                Ok(true)
            }
            Err(e) => Err(e),
        }
    }
}

/// The encoders for `subtype` of one kind (MFT_ENUM_FLAG_SYNCMFT or MFT_ENUM_FLAG_HARDWARE), best first.
unsafe fn encoders(subtype: windows::core::GUID, kind: MFT_ENUM_FLAG) -> windows::core::Result<Vec<IMFActivate>> {
    let input = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_NV12 };
    let output = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: subtype };
    let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
    let mut count = 0u32;
    // Hardware encoders are listed without an input type: some only list their own GPU formats, yet accept
    // NV12 in system memory once configured (open() finds out).
    let input = if kind == MFT_ENUM_FLAG_HARDWARE { None } else { Some(&input as *const _) };
    MFTEnumEx(
        MFT_CATEGORY_VIDEO_ENCODER,
        kind | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
        input,
        Some(&output),
        &mut activates,
        &mut count,
    )?;
    let list = std::slice::from_raw_parts(activates, count as usize);
    let found = list.iter().filter_map(|a| std::ptr::read(a)).collect();
    CoTaskMemFree(Some(activates as *const _));
    Ok(found)
}

impl Drop for Encoder {
    fn drop(&mut self) {
        unsafe {
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
            let _ = self.activate.ShutdownObject();
        }
    }
}

/// Does this Annex-B stream contain the parameter sets (H.264: SPS, NAL type 7; H.265: VPS, NAL type 32)?
fn has_parameter_sets(b: &[u8], hevc: bool) -> bool {
    b.windows(4).any(|w| {
        w[0] == 0 && w[1] == 0 && w[2] == 1 && if hevc { (w[3] >> 1) & 0x3F == 32 } else { w[3] & 0x1F == 7 }
    })
}

/// BGRA → NV12, BT.601 limited range.
fn bgra_to_nv12(src: &[u8], stride: usize, w: usize, h: usize, dst: &mut [u8]) {
    let (luma, chroma) = dst.split_at_mut(w * h);
    for y in 0..h {
        let row = &src[y * stride..y * stride + w * 4];
        let out = &mut luma[y * w..(y + 1) * w];
        for (x, px) in row.chunks_exact(4).enumerate() {
            let (b, g, r) = (px[0] as i32, px[1] as i32, px[2] as i32);
            out[x] = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) as u8;
        }
    }
    for y in (0..h).step_by(2) {
        let r0 = &src[y * stride..];
        let r1 = &src[(y + 1).min(h - 1) * stride..];
        let out = &mut chroma[(y / 2) * w..(y / 2) * w + w];
        for x in (0..w).step_by(2) {
            let i = x * 4;
            let j = (x + 1).min(w - 1) * 4;
            let b = r0[i] as i32 + r0[j] as i32 + r1[i] as i32 + r1[j] as i32;
            let g = r0[i + 1] as i32 + r0[j + 1] as i32 + r1[i + 1] as i32 + r1[j + 1] as i32;
            let r = r0[i + 2] as i32 + r0[j + 2] as i32 + r1[i + 2] as i32 + r1[j + 2] as i32;
            // Sums of 4 pixels: divide by 4 (>> 2) on top of the >> 8 fixed point.
            out[x] = ((((-38 * r - 74 * g + 112 * b) >> 2) + 128 * 256 + 128) >> 8).clamp(0, 255) as u8;
            out[x + 1] = ((((112 * r - 94 * g - 18 * b) >> 2) + 128 * 256 + 128) >> 8).clamp(0, 255) as u8;
        }
    }
}

// ---------- C ABI ----------

/// Returns an encoder handle, or 0 (see airlink_last_error). Width/height are rounded down to even numbers.
/// `codec`: 2 = H.264, 4 = H.265 (the protocol's codec numbers).
#[no_mangle]
pub extern "C" fn airvideo_new(width: u32, height: u32, fps: u32, bitrate: u32, codec: u32) -> *mut Encoder {
    match Encoder::new(width & !1, height & !1, fps, bitrate, codec) {
        Ok(e) => Box::into_raw(Box::new(e)),
        Err(e) => {
            let name = if codec == CODEC_H265 { "H.265" } else { "H.264" };
            crate::set_error(format!("{name} encoder unavailable: {e}"));
            std::ptr::null_mut()
        }
    }
}

/// Encodes a BGRA frame. On success returns 0 and points `out`/`out_len` at the encoded bytes (valid until
/// the next call); `key` = 1 if they contain a keyframe. Returns -1 on error (see airlink_last_error).
#[no_mangle]
pub extern "C" fn airvideo_encode(
    enc: *mut Encoder, bgra: *const u8, stride: usize, keyframe: i32,
    out: *mut *const u8, out_len: *mut usize, key: *mut i32,
) -> i32 {
    let enc = unsafe { &mut *enc };
    let input = unsafe { std::slice::from_raw_parts(bgra, stride * enc.height as usize) };
    match enc.encode(input, stride, keyframe != 0) {
        Ok((bytes, k)) => unsafe {
            *out = bytes.as_ptr();
            *out_len = bytes.len();
            *key = k as i32;
            0
        },
        Err(e) => {
            crate::set_error(format!("Video encoding failed: {e}"));
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn airvideo_free(enc: *mut Encoder) {
    if !enc.is_null() {
        drop(unsafe { Box::from_raw(enc) });
    }
}
