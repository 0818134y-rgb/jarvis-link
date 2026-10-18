"""音声入出力: faster-whisper による聞き取りと VOICEVOX による読み上げ。"""
from __future__ import annotations

import io
import queue
import re
import threading
import time

import numpy as np
import requests

SAMPLE_RATE = 16000
FRAME_MS = 30
FRAME = SAMPLE_RATE * FRAME_MS // 1000

# Whisperが無音や雑音で出しがちな幻聴
HALLUCINATIONS = ("ご視聴ありがとうございました", "チャンネル登録", "おやすみなさい。", "字幕", "ありがとうございました。")


class Listener:
    """マイクを常時開き、発話区間を切り出して文字起こしする。"""

    def __init__(self, cfg: dict):
        from faster_whisper import WhisperModel

        a = cfg["asr"]
        device = a.get("device", "auto")
        compute = a.get("compute_type", "auto")
        if device == "auto":
            try:
                import ctranslate2
                device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
            except Exception:  # noqa: BLE001
                device = "cpu"
        if compute == "auto":
            compute = "float16" if device == "cuda" else "int8"
        print(f"[asr] loading faster-whisper '{a['model']}' on {device}/{compute} ...")
        self.model = WhisperModel(a["model"], device=device, compute_type=compute)
        self.q: queue.Queue[np.ndarray] = queue.Queue()
        self.muted = threading.Event()
        self.threshold = 0.012
        self.noise = 0.004
        self.stream = None
        self.last_start = 0.0  # 直近の発話が始まった時刻

    def start(self) -> None:
        import sounddevice as sd

        def cb(indata, frames, t, status):
            if not self.muted.is_set():
                self.q.put(indata[:, 0].copy())

        self.stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                                     blocksize=FRAME, callback=cb)
        self.stream.start()
        self._calibrate()

    def _calibrate(self) -> None:
        levels = []
        end = time.time() + 1.0
        while time.time() < end:
            try:
                levels.append(float(np.sqrt(np.mean(self.q.get(timeout=0.5) ** 2))))
            except queue.Empty:
                break
        if levels:
            self.noise = float(np.percentile(levels, 60))
            self._update_threshold()
        print(f"[asr] mic threshold = {self.threshold:.4f}")

    def _update_threshold(self) -> None:
        # 部屋の雑音の 2.5 倍を発話の目安にする (小さすぎ・大きすぎは制限)
        self.threshold = min(0.08, max(0.006, self.noise * 2.5))

    def flush(self) -> None:
        while not self.q.empty():
            try:
                self.q.get_nowait()
            except queue.Empty:
                break

    def record_utterance(self, timeout: float | None = None, max_sec: float = 15.0,
                         silence_sec: float = 0.8) -> np.ndarray | None:
        """発話が始まるまで待ち(timeout秒で諦める)、無音が続いたら区切って返す。"""
        pre: list[np.ndarray] = []
        voiced: list[np.ndarray] = []
        started = False
        silent = 0
        start_wait = time.time()
        while True:
            try:
                frame = self.q.get(timeout=0.5)
            except queue.Empty:
                if not started and timeout and time.time() - start_wait > timeout:
                    return None
                continue
            level = float(np.sqrt(np.mean(frame ** 2)))
            if not started:
                # 話していない間に雑音レベルを少しずつ追従させる (エアコン・換気扇などの変化に強く)
                self.noise = self.noise * 0.995 + level * 0.005
                self._update_threshold()
                pre.append(frame)
                pre = pre[-12:]  # 発話直前の360msも含める
                if level > self.threshold:
                    started = True
                    self.last_start = time.time()
                    voiced = list(pre)
                elif timeout and time.time() - start_wait > timeout:
                    return None
                continue
            voiced.append(frame)
            silent = silent + 1 if level < self.threshold * 0.6 else 0
            dur = len(voiced) * FRAME_MS / 1000
            if silent * FRAME_MS / 1000 >= silence_sec or dur >= max_sec:
                break
        audio = np.concatenate(voiced)
        if len(audio) < SAMPLE_RATE * 0.3:  # 短すぎる物音は捨てる
            return None
        return audio

    def transcribe(self, audio: np.ndarray) -> str:
        segments, _ = self.model.transcribe(
            audio, language="ja", beam_size=3, vad_filter=True,
            condition_on_previous_text=False,
            initial_prompt="ジャーヴィス、スマホのライトをつけて。ジャーヴィス、メモ帳を開いて。ジャーヴィス、今日の天気は？")
        text = "".join(s.text for s in segments).strip()
        if any(h in text for h in HALLUCINATIONS) and len(text) < 25:
            return ""
        return text

    def listen(self, timeout: float | None = None) -> str:
        audio = self.record_utterance(timeout=timeout)
        if audio is None:
            return ""
        return self.transcribe(audio)


class Speaker:
    """VOICEVOX で読み上げ。起動していなければ Windows 標準音声 (pyttsx3)。"""

    def __init__(self, cfg: dict, listener: Listener | None = None):
        t = cfg["tts"]
        self.host = t["voicevox_host"].rstrip("/")
        self.speaker = int(t.get("speaker", 13))
        self.speed = float(t.get("speed", 1.1))
        self.listener = listener
        self._lock = threading.Lock()
        self._fallback = None

    def _voicevox(self, text: str) -> bytes:
        q = requests.post(f"{self.host}/audio_query", params={"text": text, "speaker": self.speaker},
                          timeout=20)
        q.raise_for_status()
        query = q.json()
        query["speedScale"] = self.speed
        w = requests.post(f"{self.host}/synthesis", params={"speaker": self.speaker},
                          json=query, timeout=60)
        w.raise_for_status()
        return w.content

    def say(self, text: str) -> None:
        text = text.strip()
        if not text:
            return
        print(f"JARVIS> {text}")
        with self._lock:
            if self.listener:
                self.listener.muted.set()
            try:
                for chunk in _split_sentences(text):
                    try:
                        wav = self._voicevox(chunk)
                        _play_wav(wav)
                    except requests.RequestException:
                        self._say_fallback(chunk)
            finally:
                time.sleep(0.25)
                if self.listener:
                    self.listener.flush()
                    self.listener.muted.clear()

    def _say_fallback(self, text: str) -> None:
        try:
            import pyttsx3
            if self._fallback is None:
                self._fallback = pyttsx3.init()
                # 男性の声 (Ichiro など) があれば優先
                for v in self._fallback.getProperty("voices"):
                    n = (v.name or "") + (v.id or "")
                    if any(k in n for k in ("Ichiro", "Male", "男性", "Keita", "Daichi")):
                        self._fallback.setProperty("voice", v.id)
                        break
                self._fallback.setProperty("rate", 185)
            self._fallback.say(text)
            self._fallback.runAndWait()
        except Exception as e:  # noqa: BLE001
            print(f"[tts] unavailable: {e}")

    def beep(self, freq: int = 880, ms: int = 120) -> None:
        import sounddevice as sd
        t = np.linspace(0, ms / 1000, int(24000 * ms / 1000), False)
        tone = 0.2 * np.sin(2 * np.pi * freq * t) * np.hanning(len(t))
        sd.play(tone.astype(np.float32), 24000)
        sd.wait()


def _split_sentences(text: str) -> list[str]:
    parts = [p for p in re.split(r"(?<=[。！？!?\n])", text) if p.strip()]
    return parts or [text]


def _play_wav(wav: bytes) -> None:
    import sounddevice as sd
    import soundfile as sf
    data, sr = sf.read(io.BytesIO(wav), dtype="float32")
    sd.play(data, sr)
    sd.wait()
