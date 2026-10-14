//! airlink: AirPointer's internet transport.
//!
//! A thin, blocking wrapper around [iroh] (peer-to-peer QUIC: direct connections through NAT hole punching,
//! with iroh's public relays as fallback, and lookup of a peer by its key alone). Each AirPointer connection
//! (control, screen view, sound) is one iroh connection carrying one bidirectional stream, over which the
//! normal AirPointer protocol runs unchanged.
//!
//! Exposed as a C ABI (`airlink_*`, used by the .NET desktop app) and as JNI (`Airlink` class, Android).
//! Endpoints and streams are referred to by numeric handles kept in a registry, so a late call after
//! `close`/`stop` simply fails instead of touching freed memory.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use iroh::endpoint::{presets, Connection, RecvStream, SendStream, VarInt};
use iroh::{Endpoint, EndpointId, SecretKey};
use tokio::runtime::Runtime;
use tokio::sync::{mpsc, Mutex as AsyncMutex};
use tokio::time::timeout;

#[cfg(target_os = "android")]
mod jni_api;

#[cfg(windows)]
mod video_win;

/// ALPN of the AirPointer protocol over iroh.
pub const ALPN: &[u8] = b"airpointer/1";

static RT: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("airlink")
        .build()
        .expect("tokio runtime")
});

struct Node {
    ep: Endpoint,
    /// Accepted streams (listening endpoints only).
    incoming: Option<AsyncMutex<mpsc::Receiver<(Arc<Pipe>, [u8; 32])>>>,
}

struct Pipe {
    conn: Connection,
    send: AsyncMutex<SendStream>,
    recv: AsyncMutex<RecvStream>,
}

static NEXT: AtomicU64 = AtomicU64::new(1);
static NODES: LazyLock<Mutex<HashMap<u64, Arc<Node>>>> = LazyLock::new(Default::default);
static PIPES: LazyLock<Mutex<HashMap<u64, Arc<Pipe>>>> = LazyLock::new(Default::default);

fn put<T>(map: &Mutex<HashMap<u64, Arc<T>>>, v: Arc<T>) -> u64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    map.lock().unwrap().insert(id, v);
    id
}

fn get<T>(map: &Mutex<HashMap<u64, Arc<T>>>, id: u64) -> Option<Arc<T>> {
    map.lock().unwrap().get(&id).cloned()
}

fn take<T>(map: &Mutex<HashMap<u64, Arc<T>>>, id: u64) -> Option<Arc<T>> {
    map.lock().unwrap().remove(&id)
}

fn ms(t: u32) -> Duration {
    Duration::from_millis(t as u64)
}

/// The blocking API both bindings share. Errors are human-readable strings.
pub mod core {
    use super::*;

    /// Starts an endpoint with the given 32-byte secret key. `listen` = accept incoming connections (the PC).
    pub fn start(secret: &[u8; 32], listen: bool) -> Result<u64, String> {
        RT.block_on(async {
            let ep = Endpoint::builder(presets::N0)
                .secret_key(SecretKey::from_bytes(secret))
                .alpns(if listen { vec![ALPN.to_vec()] } else { vec![] })
                .bind()
                .await
                .map_err(|e| format!("Couldn't start the internet connection: {e}"))?;
            let incoming = if listen {
                let (tx, rx) = mpsc::channel(16);
                tokio::spawn(accept_loop(ep.clone(), tx));
                Some(AsyncMutex::new(rx))
            } else {
                None
            };
            Ok(put(&NODES, Arc::new(Node { ep, incoming })))
        })
    }

    async fn accept_loop(ep: Endpoint, tx: mpsc::Sender<(Arc<Pipe>, [u8; 32])>) {
        while let Some(incoming) = ep.accept().await {
            let tx = tx.clone();
            tokio::spawn(async move {
                let Ok(conn) = incoming.await else { return };
                // The phone opens one stream and speaks first (Hello), so this resolves promptly.
                let Ok(Ok((send, recv))) = timeout(Duration::from_secs(15), conn.accept_bi()).await else {
                    conn.close(VarInt::from_u32(1), b"no stream");
                    return;
                };
                let remote = *conn.remote_id().as_bytes();
                let pipe = Arc::new(Pipe { conn, send: AsyncMutex::new(send), recv: AsyncMutex::new(recv) });
                let _ = tx.send((pipe, remote)).await;
            });
        }
    }

    /// This endpoint's id (its public key).
    pub fn id(node: u64) -> Option<[u8; 32]> {
        get(&NODES, node).map(|n| *n.ep.id().as_bytes())
    }

    /// Waits until the endpoint is reachable from the internet (connected to its home relay).
    pub fn online(node: u64, timeout_ms: u32) -> bool {
        let Some(n) = get(&NODES, node) else { return false };
        RT.block_on(async { timeout(ms(timeout_ms), n.ep.online()).await.is_ok() })
    }

    pub enum Accepted {
        Pipe(u64, [u8; 32]),
        Timeout,
        Stopped,
    }

    /// Next incoming stream, with the remote endpoint id.
    pub fn accept(node: u64, timeout_ms: u32) -> Accepted {
        let Some(n) = get(&NODES, node) else { return Accepted::Stopped };
        let Some(incoming) = n.incoming.as_ref() else { return Accepted::Stopped };
        RT.block_on(async {
            let mut rx = incoming.lock().await;
            match timeout(ms(timeout_ms), rx.recv()).await {
                Err(_) => Accepted::Timeout,
                Ok(None) => Accepted::Stopped,
                Ok(Some((pipe, remote))) => Accepted::Pipe(put(&PIPES, pipe), remote),
            }
        })
    }

    /// Connects to the endpoint with this id (address found through iroh's address lookup).
    pub fn connect(node: u64, peer: &[u8; 32], timeout_ms: u32) -> Result<u64, String> {
        let n = get(&NODES, node).ok_or("Internet connection not started")?;
        let peer = EndpointId::from_bytes(peer).map_err(|e| format!("Bad computer id: {e}"))?;
        RT.block_on(async {
            let attempt = async {
                // A computer that only just came online may not be published yet: retry the lookup briefly.
                let conn = loop {
                    match n.ep.connect(peer, ALPN).await {
                        Ok(c) => break c,
                        Err(e) if e.to_string().contains("No addressing information") => {
                            tokio::time::sleep(Duration::from_secs(1)).await
                        }
                        Err(e) => return Err(format!("{e}")),
                    }
                };
                let (send, recv) = conn.open_bi().await.map_err(|e| format!("{e}"))?;
                Ok::<_, String>(Pipe { conn, send: AsyncMutex::new(send), recv: AsyncMutex::new(recv) })
            };
            match timeout(ms(timeout_ms), attempt).await {
                Err(_) => Err("The computer didn't answer over the internet".to_string()),
                Ok(Err(e)) => Err(format!("Couldn't reach the computer over the internet: {e}")),
                Ok(Ok(pipe)) => Ok(put(&PIPES, Arc::new(pipe))),
            }
        })
    }

    pub const READ_EOF: i64 = 0;
    pub const READ_ERROR: i64 = -1;
    pub const READ_TIMEOUT: i64 = -2;

    /// Reads up to `buf.len()` bytes: >0 = count, 0 = end of stream, -1 = error/closed, -2 = timeout.
    pub fn read(pipe: u64, buf: &mut [u8], timeout_ms: u32) -> i64 {
        let Some(p) = get(&PIPES, pipe) else { return READ_ERROR };
        RT.block_on(async {
            let mut recv = p.recv.lock().await;
            match timeout(ms(timeout_ms), recv.read(buf)).await {
                Err(_) => READ_TIMEOUT,
                Ok(Ok(Some(n))) => n as i64,
                Ok(Ok(None)) => READ_EOF,
                Ok(Err(_)) => READ_ERROR,
            }
        })
    }

    /// Writes all bytes (blocks while the peer is slow, up to `timeout_ms`). Returns false on error.
    pub fn write(pipe: u64, buf: &[u8], timeout_ms: u32) -> bool {
        let Some(p) = get(&PIPES, pipe) else { return false };
        RT.block_on(async {
            let mut send = p.send.lock().await;
            matches!(timeout(ms(timeout_ms), send.write_all(buf)).await, Ok(Ok(())))
        })
    }

    /// 1 = a direct (hole-punched) path is in use, 0 = only via a relay, -1 = unknown/closed.
    pub fn direct(pipe: u64) -> i32 {
        let Some(p) = get(&PIPES, pipe) else { return -1 };
        let paths = p.conn.paths();
        if paths.iter().any(|path| !path.is_relay()) {
            1
        } else {
            0
        }
    }

    /// Ends the stream (pending data is still delivered), then the connection. The handle becomes invalid.
    pub fn close(pipe: u64) {
        let Some(p) = take(&PIPES, pipe) else { return };
        RT.spawn(async move {
            if let Ok(mut send) = p.send.try_lock() {
                let _ = send.finish();
            }
            // Give the peer a moment to read what's left and close its side.
            let _ = timeout(Duration::from_secs(2), p.conn.closed()).await;
            p.conn.close(VarInt::from_u32(0), b"bye");
        });
    }

    /// Tells the endpoint the device's network may have changed (Wi-Fi <-> mobile data, new address), so it
    /// rebinds and moves its connections and relay link over right away. Android can't detect this from
    /// native code, so the app reports it (iroh's own advice); elsewhere it's harmless.
    pub fn network_change(node: u64) {
        let Some(n) = get(&NODES, node) else { return };
        RT.block_on(n.ep.network_change());
    }

    pub fn stop(node: u64) {
        let Some(n) = take(&NODES, node) else { return };
        RT.block_on(async { timeout(Duration::from_secs(3), n.ep.close()).await.ok() });
    }
}

// ---------- C ABI (desktop) ----------

thread_local! {
    static LAST_ERROR: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
}

pub(crate) fn set_error(e: String) {
    LAST_ERROR.with(|l| *l.borrow_mut() = e);
}

/// Copies this thread's last error message (UTF-8) into `buf`; returns its full length.
#[no_mangle]
pub extern "C" fn airlink_last_error(buf: *mut u8, len: usize) -> usize {
    LAST_ERROR.with(|l| {
        let s = l.borrow();
        let n = s.len().min(len);
        if !buf.is_null() && n > 0 {
            unsafe { std::ptr::copy_nonoverlapping(s.as_ptr(), buf, n) };
        }
        s.len()
    })
}

/// `secret`: 32 bytes. Returns a node handle, or 0 (see airlink_last_error).
#[no_mangle]
pub extern "C" fn airlink_start(secret: *const u8, listen: i32) -> u64 {
    let secret: &[u8; 32] = unsafe { &*(secret as *const [u8; 32]) };
    core::start(secret, listen != 0).unwrap_or_else(|e| {
        set_error(e);
        0
    })
}

/// Writes the 32-byte endpoint id to `out`. Returns 0, or -1 if the node is gone.
#[no_mangle]
pub extern "C" fn airlink_id(node: u64, out: *mut u8) -> i32 {
    match core::id(node) {
        Some(id) => {
            unsafe { std::ptr::copy_nonoverlapping(id.as_ptr(), out, 32) };
            0
        }
        None => -1,
    }
}

#[no_mangle]
pub extern "C" fn airlink_online(node: u64, timeout_ms: u32) -> i32 {
    core::online(node, timeout_ms) as i32
}

/// Returns a pipe handle (>0, remote id written to `remote`, 32 bytes), 0 on timeout, -1 when stopped.
#[no_mangle]
pub extern "C" fn airlink_accept(node: u64, timeout_ms: u32, remote: *mut u8) -> i64 {
    match core::accept(node, timeout_ms) {
        core::Accepted::Pipe(p, id) => {
            unsafe { std::ptr::copy_nonoverlapping(id.as_ptr(), remote, 32) };
            p as i64
        }
        core::Accepted::Timeout => 0,
        core::Accepted::Stopped => -1,
    }
}

/// Returns a pipe handle, or 0 (see airlink_last_error).
#[no_mangle]
pub extern "C" fn airlink_connect(node: u64, peer: *const u8, timeout_ms: u32) -> u64 {
    let peer: &[u8; 32] = unsafe { &*(peer as *const [u8; 32]) };
    core::connect(node, peer, timeout_ms).unwrap_or_else(|e| {
        set_error(e);
        0
    })
}

#[no_mangle]
pub extern "C" fn airlink_read(pipe: u64, buf: *mut u8, len: usize, timeout_ms: u32) -> i64 {
    let buf = unsafe { std::slice::from_raw_parts_mut(buf, len) };
    core::read(pipe, buf, timeout_ms)
}

/// Returns 0 when everything was written, -1 on error/timeout.
#[no_mangle]
pub extern "C" fn airlink_write(pipe: u64, buf: *const u8, len: usize, timeout_ms: u32) -> i32 {
    let buf = unsafe { std::slice::from_raw_parts(buf, len) };
    if core::write(pipe, buf, timeout_ms) { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn airlink_direct(pipe: u64) -> i32 {
    core::direct(pipe)
}

#[no_mangle]
pub extern "C" fn airlink_close(pipe: u64) {
    core::close(pipe)
}

#[no_mangle]
pub extern "C" fn airlink_stop(node: u64) {
    core::stop(node)
}
