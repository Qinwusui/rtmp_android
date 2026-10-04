#!/usr/bin/env python3
"""Inspect live HTTP-FLV in memory. Outputs metadata/timestamps, never stores audio/video."""
import argparse, json, statistics, time, urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('url')
parser.add_argument('--seconds', type=float, default=10)
args = parser.parse_args()

def exact(stream, size):
    result = bytearray()
    while len(result) < size:
        chunk = stream.read(size - len(result))
        if not chunk:
            raise RuntimeError('stream disconnected')
        result.extend(chunk)
    return result

class Bits:
    def __init__(self, data):
        self.bits = ''.join(f'{b:08b}' for b in data)
        self.pos = 0
    def read(self, count):
        value = int(self.bits[self.pos:self.pos+count], 2) if count else 0
        self.pos += count
        return value
    def ue(self):
        zero = 0
        while self.read(1) == 0:
            zero += 1
            if zero > 31:
                raise ValueError('invalid Exp-Golomb')
        return (1 << zero) - 1 + self.read(zero)
    def se(self):
        value = self.ue()
        return (value+1)//2 if value & 1 else -value//2

def sps_dimensions(data):
    rbsp = data[1:].replace(b'\x00\x00\x03', b'\x00\x00')
    b = Bits(rbsp)
    profile = b.read(8); b.read(16); b.ue()
    chroma = 1
    if profile in (100,110,122,244,44,83,86,118,128,138,139,134,135):
        chroma = b.ue()
        if chroma == 3: b.read(1)
        b.ue(); b.ue(); b.read(1)
        if b.read(1):
            for index in range(8 if chroma != 3 else 12):
                if b.read(1):
                    last = next_scale = 8
                    for _ in range(16 if index < 6 else 64):
                        if next_scale != 0: next_scale = (last+b.se()+256)%256
                        last = next_scale or last
    b.ue()
    order = b.ue()
    if order == 0:
        b.ue()
    elif order == 1:
        b.read(1); b.se(); b.se()
        for _ in range(b.ue()): b.se()
    b.ue(); b.read(1)
    width = (b.ue() + 1) * 16
    height = (b.ue() + 1) * 16
    frame = b.read(1)
    if not frame: b.read(1)
    b.read(1)
    crop = [b.ue() for _ in range(4)] if b.read(1) else [0]*4
    crop_x = 2 if chroma in (1,2) else 1
    crop_y = (2 if chroma == 1 else 1)*(2-frame)
    return {'profile': profile, 'width': width - (crop[0]+crop[1])*crop_x,
            'height': height*(2-frame) - (crop[2]+crop[3])*crop_y}

video, audio = [], []
sizes = 0
configuration = {}
start = time.monotonic()
with urllib.request.urlopen(args.url, timeout=15) as stream:
    header = exact(stream, 9)
    if header[:3] != b'FLV': raise ValueError('not FLV')
    exact(stream, int.from_bytes(header[5:9], 'big')-9+4)
    while time.monotonic() - start < args.seconds:
        tag = exact(stream, 11)
        size = int.from_bytes(tag[1:4], 'big')
        if size > 8*1024*1024: raise ValueError('oversized FLV tag')
        dts = int.from_bytes(tag[4:7], 'big') + (tag[7] << 24)
        data = exact(stream, size); exact(stream, 4)
        if tag[0] == 9 and len(data) >= 5 and data[0] & 15 == 7:
            if data[1] == 0:
                avc = data[5:]
                if len(avc) > 8:
                    sps_len = int.from_bytes(avc[6:8], 'big')
                    configuration['h264'] = sps_dimensions(avc[8:8+sps_len])
            elif data[1] == 1:
                cts = int.from_bytes(data[2:5], 'big')
                if cts & 0x800000: cts -= 1 << 24
                video.append((dts+cts)/1000); sizes += size
        elif tag[0] == 8 and len(data) >= 4 and data[0] >> 4 == 10:
            if data[1] == 0:
                bits = Bits(data[2:]); object_type = bits.read(5); rate_index = bits.read(4)
                rate = [96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350][rate_index] if rate_index < 13 else None
                configuration['aac'] = {'object_type':object_type,'sample_rate':rate,'channels':bits.read(4)}
            elif data[1] == 1:
                audio.append(dts/1000); sizes += size

def summary(timestamps):
    intervals = [b-a for a,b in zip(timestamps,timestamps[1:])]
    return {'packets':len(timestamps), 'monotonic':all(value>0 for value in intervals),
            'rate':round((len(timestamps)-1)/(timestamps[-1]-timestamps[0]),2) if len(timestamps)>1 else None,
            'median_interval_ms':round(statistics.median(intervals)*1000,2) if intervals else None}

duration = time.monotonic()-start
print(json.dumps({'configuration':configuration,'video':summary(video),'audio':summary(audio),
                  'payload_bitrate_mbps':round(sizes*8/duration/1_000_000,3),
                  'first_timestamp_offset_ms':round((audio[0]-video[0])*1000,2) if audio and video else None,
                  'wall_seconds':round(duration,2)}, indent=2))
