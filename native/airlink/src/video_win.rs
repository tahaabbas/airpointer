//! H.264 encoding for screen view on Windows, with Media Foundation's encoder (ships with Windows, so the
//! codec licence comes with the OS). Input is the captured BGRA screen, converted here to NV12 (BT.601,
//! limited range: the colors every decoder assumes when the stream doesn't say); output is an Annex-B byte stream, with SPS/PPS in front of every keyframe.
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

pub struct Encoder {
    mft: IMFTransform,
    codec: Option<ICodecAPI>,
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
    /// `width`/`height` must be even. `bitrate` in bits per second.
    pub fn new(width: u32, height: u32, fps: u32, bitrate: u32) -> windows::core::Result<Self> {
        com_init();
        unsafe {
            let input = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_NV12 };
            let output = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_H264 };
            let mut activates: *mut Option<IMFActivate> = std::ptr::null_mut();
            let mut count = 0u32;
            // Synchronous software encoder (Microsoft's "H264 Encoder MFT"): simple and always present.
            MFTEnumEx(
                MFT_CATEGORY_VIDEO_ENCODER,
                MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER,
                Some(&input),
                Some(&output),
                &mut activates,
                &mut count,
            )?;
            let list = std::slice::from_raw_parts(activates, count as usize);
            let mft: Option<IMFTransform> = list.iter().flatten().find_map(|a| a.ActivateObject().ok());
            for a in list {
                drop(std::ptr::read(a));
            }
            CoTaskMemFree(Some(activates as *const _));
            let mft = mft.ok_or_else(|| windows::core::Error::from(MF_E_TOPO_CODEC_NOT_FOUND))?;

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
            out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            out_type.SetUINT32(&MF_MT_AVG_BITRATE, bitrate)?;
            out_type.SetUINT64(&MF_MT_FRAME_SIZE, pack(width, height))?;
            out_type.SetUINT64(&MF_MT_FRAME_RATE, pack(fps, 1))?;
            out_type.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, pack(1, 1))?;
            out_type.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            out_type.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main.0 as u32)?;
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

            // SPS/PPS, in case the encoder doesn't repeat them in front of keyframes.
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
                codec,
                width,
                height,
                fps: fps.max(1),
                nv12: vec![0; (width * height * 3 / 2) as usize],
                out: Vec::with_capacity(1 << 20),
                frame: 0,
                sequence_header,
            })
        }
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
            self.mft.ProcessInput(0, &sample, 0)?;

            self.out.clear();
            let mut key = false;
            loop {
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
                                key = true;
                            }
                            let b = s.ConvertToContiguousBuffer()?;
                            let mut p = std::ptr::null_mut();
                            let mut len = 0u32;
                            b.Lock(&mut p, None, Some(&mut len))?;
                            self.out.extend_from_slice(std::slice::from_raw_parts(p, len as usize));
                            b.Unlock()?;
                        }
                    }
                    Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => break,
                    Err(e) if e.code() == MF_E_TRANSFORM_STREAM_CHANGE => {
                        let t = self.mft.GetOutputAvailableType(0, 0)?;
                        self.mft.SetOutputType(0, &t, 0)?;
                    }
                    Err(e) => return Err(e),
                }
            }
            if key && !self.sequence_header.is_empty() && !has_sps(&self.out) {
                let mut with = self.sequence_header.clone();
                with.extend_from_slice(&self.out);
                self.out = with;
            }
            Ok((&self.out, key))
        }
    }
}

impl Drop for Encoder {
    fn drop(&mut self) {
        unsafe {
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            let _ = self.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        }
    }
}

/// Does this Annex-B stream contain an SPS (NAL type 7)?
fn has_sps(b: &[u8]) -> bool {
    b.windows(4).any(|w| w[0] == 0 && w[1] == 0 && w[2] == 1 && (w[3] & 0x1F) == 7)
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
#[no_mangle]
pub extern "C" fn airvideo_new(width: u32, height: u32, fps: u32, bitrate: u32) -> *mut Encoder {
    match Encoder::new(width & !1, height & !1, fps, bitrate) {
        Ok(e) => Box::into_raw(Box::new(e)),
        Err(e) => {
            crate::set_error(format!("H.264 encoder unavailable: {e}"));
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
            crate::set_error(format!("H.264 encoding failed: {e}"));
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
