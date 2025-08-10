#!/usr/bin/env python3
import argparse
import logging
import queue
import selectors
import shutil
import socket
import struct
import subprocess
import threading
import time
from typing import Dict, Optional, Tuple

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logger = logging.getLogger(__name__)

MSG_TEXT = 0x01
MAX_BYTES = 1_048_576


def frame_text(text: str) -> bytes:
    data = text.encode("utf-8")
    if len(data) > MAX_BYTES:
        raise ValueError("payload too large")
    return bytes([MSG_TEXT]) + struct.pack(">I", len(data)) + data


def which_or_none(cmd: str) -> Optional[str]:
    return shutil.which(cmd)


def read_clipboard_text(timeout: float = 1.0) -> Tuple[bool, Optional[str]]:
    tools = [
        ("wl-paste", ["--type", "text", "--no-newline"], "wl-paste read failed: %s"),
        ("xclip", ["-selection", "clipboard", "-out"], "xclip read failed: %s"),
        ("xsel", ["--clipboard", "--output"], "xsel read failed: %s"),
    ]

    for name, args, err_msg in tools:
        path = which_or_none(name)
        if not path:
            continue
        try:
            cp = subprocess.run(
                [path, *args],
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
            if cp.returncode == 0:
                return True, cp.stdout
        except Exception as e:
            logger.debug(err_msg, e)
            return False, None

    return False, None


def write_clipboard_text(text: str) -> bool:
    tools = [
        ("wl-copy", ["--type", "text"], "wl-copy write failed: %s"),
        ("xclip", ["-selection", "clipboard", "-in"], "xclip write failed: %s"),
        ("xsel", ["--clipboard", "--input"], "xsel write failed: %s"),
    ]

    for name, args, err_msg in tools:
        path = which_or_none(name)
        if not path:
            continue
        try:
            subprocess.run(
                [path, *args],
                input=text,
                text=True,
                check=False,
                timeout=1.0,
            )
            return True
        except Exception as e:
            logger.debug(err_msg, e)

    logger.warning("No clipboard writer available")
    return False


class ClientState:
    def __init__(self, sock: socket.socket, label: str):
        self.sock = sock
        self.label = label
        self.rbuf = bytearray()


class BridgeServer:
    def __init__(self, host: str, port: int, poll_ms: int):
        self.host = host
        self.port = port
        self.poll_interval = max(50, poll_ms) / 1000.0
        self.sel = selectors.DefaultSelector()
        self.server: Optional[socket.socket] = None
        self.clients: Dict[socket.socket, ClientState] = {}
        self.to_broadcast: "queue.Queue[bytes]" = queue.Queue()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._last_polled: Optional[str] = None
        self._last_sent_from_pc: Optional[str] = None

    def start(self) -> None:
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((self.host, self.port))
        self.server.listen(8)
        self.server.setblocking(False)
        self.sel.register(self.server, selectors.EVENT_READ)

        logger.info("Server listening on %s:%d", self.host, self.port)

        t = threading.Thread(target=self._clipboard_watch_loop, daemon=True)
        t.start()
        logger.info("Clipboard watcher started (poll=%dms)", int(self.poll_interval * 1000))

        try:
            self._serve_loop()
        except KeyboardInterrupt:
            logger.info("Interrupted, shutting down…")
        finally:
            self.shutdown()

    def shutdown(self) -> None:
        self._stop.set()
        try:
            self.sel.close()
        except Exception:
            pass
        with self._lock:
            count = len(self.clients)
            for st in list(self.clients.values()):
                try:
                    st.sock.close()
                except Exception:
                    pass
            self.clients.clear()
        if self.server:
            try:
                self.server.close()
            except Exception:
                pass
            self.server = None
        logger.info("Shutdown complete, closed %d client(s)", count)

    def _serve_loop(self) -> None:
        while not self._stop.is_set():
            events = self.sel.select(timeout=1.0)
            for key, mask in events:
                if key.fileobj is self.server:
                    self._accept()
                else:
                    self._handle_client(key.fileobj)  # type: ignore[arg-type]
            self._broadcast_pending()

    def _accept(self) -> None:
        assert self.server is not None
        conn, addr = self.server.accept()
        conn.setblocking(False)
        self.sel.register(conn, selectors.EVENT_READ)
        state = ClientState(conn, f"{addr[0]}:{addr[1]}")
        with self._lock:
            self.clients[conn] = state
        logger.info("Client connected: %s (clients=%d)", state.label, len(self.clients))

        ok, txt = read_clipboard_text(timeout=0.5)
        if ok and txt is not None and txt != "":
            try:
                conn.sendall(frame_text(txt))
                logger.debug("Pushed initial clipboard (%d bytes) to %s", len(txt.encode()), state.label)
            except BlockingIOError:
                logger.warning("Initial send would block -> dropping %s", state.label)
                self._drop_client(conn)
            except Exception as e:
                logger.warning("Initial send failed for %s: %s", state.label, e)
                self._drop_client(conn)

    def _handle_client(self, sock: socket.socket) -> None:
        state = self.clients.get(sock)
        if not state:
            self._drop_client(sock)
            return
        try:
            chunk = sock.recv(65536)
        except BlockingIOError:
            return
        except Exception as e:
            logger.info("Recv error from %s: %s", state.label, e)
            self._drop_client(sock)
            return

        if not chunk:
            logger.info("Client closed: %s", state.label)
            self._drop_client(sock)
            return

        state.rbuf.extend(chunk)
        logger.debug("Received %d bytes from %s (buffer=%d)", len(chunk), state.label, len(state.rbuf))
        self._process_frames(state)

    def _process_frames(self, state: ClientState) -> None:
        buf = state.rbuf
        while True:
            if len(buf) < 5:
                break
            if buf[0] != MSG_TEXT:
                logger.warning("Bad frame type from %s", state.label)
                self._drop_client(state.sock)
                return
            length = struct.unpack(">I", buf[1:5])[0]
            if length < 0 or length > MAX_BYTES:
                logger.warning("Bad frame length %d from %s", length, state.label)
                self._drop_client(state.sock)
                return
            if len(buf) < 5 + length:
                break
            payload = bytes(buf[5 : 5 + length])
            del buf[: 5 + length]
            try:
                text = payload.decode("utf-8")
            except Exception as e:
                logger.warning("UTF-8 decode failed from %s: %s", state.label, e)
                continue
            self._on_text_from_client(state.sock, text)

    def _on_text_from_client(self, sender: socket.socket, text: str) -> None:
        self._last_sent_from_pc = text
        ok = write_clipboard_text(text)
        logger.info(
            "Applied text from client (%d bytes, ok=%s)",
            len(text.encode("utf-8")),
            ok,
        )
        self._broadcast(frame_text(text), exclude=sender)

    def _drop_client(self, sock: socket.socket) -> None:
        with self._lock:
            st = self.clients.pop(sock, None)
        try:
            self.sel.unregister(sock)
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass
        if st:
            logger.info("Client dropped: %s (clients=%d)", st.label, len(self.clients))

    def _broadcast(self, payload: bytes, exclude: Optional[socket.socket] = None) -> None:
        with self._lock:
            targets = list(self.clients.keys())
        sent = 0
        for c in targets:
            if c is exclude:
                continue
            try:
                c.sendall(payload)
                sent += 1
            except BlockingIOError:
                logger.info("Send would block -> dropping client")
                self._drop_client(c)
            except Exception as e:
                logger.info("Send failed -> dropping client: %s", e)
                self._drop_client(c)
        if sent:
            logger.debug("Broadcasted frame to %d client(s)", sent)

    def _broadcast_pending(self) -> None:
        flushed = 0
        while True:
            try:
                payload = self.to_broadcast.get_nowait()
            except queue.Empty:
                break
            self._broadcast(payload)
            flushed += 1
        if flushed:
            logger.debug("Flushed %d queued frame(s)", flushed)

    def queue_from_pc(self, text: str) -> None:
        self._last_sent_from_pc = text
        self.to_broadcast.put(frame_text(text))
        logger.info("Queued clipboard change from PC (%d bytes)", len(text.encode("utf-8")))

    def _clipboard_watch_loop(self) -> None:
        while not self._stop.is_set():
            ok, txt = read_clipboard_text(timeout=1.0)
            if ok:
                if txt is None:
                    pass
                else:
                    if self._last_polled is None or txt != self._last_polled:
                        if txt != self._last_sent_from_pc:
                            if txt != "":
                                self.queue_from_pc(txt)
                        self._last_polled = txt
            else:
                logger.debug("Clipboard read returned not-ok")
            time.sleep(self.poll_interval)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=28900)
    p.add_argument(
        "--poll-ms",
        type=int,
        default=300,
        help="clipboard poll interval in milliseconds",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    logger.info("Starting ClipBridge on %s:%d (poll=%dms)", args.host, args.port, args.poll_ms)
    srv = BridgeServer(args.host, args.port, args.poll_ms)
    srv.start()


if __name__ == "__main__":
    main()

