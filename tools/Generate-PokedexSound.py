"""Arranque robótico original: voz sintética con formantes y modulación metálica."""
import math
import struct
import wave
from pathlib import Path

rate = 22050
signal = []
# Tres sílabas mecánicas graves, sin melodía ni grabaciones externas.
for duration, start, end, formants in [(0.16, 105, 138, (420, 1180, 2050)),
                                      (0.025, 0, 0, (0, 0, 0)),
                                      (0.16, 138, 110, (640, 1560, 2300)),
                                      (0.025, 0, 0, (0, 0, 0)),
                                      (0.30, 112, 78, (360, 920, 1800))]:
    count = int(rate * duration)
    for i in range(count):
        t = i / rate
        envelope = min(1, t / 0.012, (duration - t) / 0.035)
        phase = 2 * math.pi * (start * t + (end - start) * t * t / (2 * duration))
        value = 0.0
        if start:
            pitch = start + (end - start) * t / duration
            for harmonic in range(1, 27):
                frequency = pitch * harmonic
                weight = 0.12 / harmonic + sum(math.exp(-((frequency - formant) / 180) ** 2) for formant in formants) / 8
                value += weight * math.sin(phase * harmonic)
            # Modulación en anillo: textura de robot, no pitidos de notificación.
            value *= 0.65 + 0.35 * math.sin(2 * math.pi * 43 * t)
        signal.append(envelope * value)
peak = max(abs(value) for value in signal)
samples = [round(21000 * value / peak) for value in signal]
target = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/pokedex_scan.wav'
target.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(target), 'wb') as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(rate)
    output.writeframes(b''.join(struct.pack('<h', sample) for sample in samples))
print(f'Sonido generado: {len(samples) / rate:.2f} s')
