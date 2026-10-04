#!/usr/bin/env python3
"""Read-only sampling of an already started real-device stream. Does not record media."""
import argparse, datetime, json, pathlib, re, subprocess, time, urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default='adb')
parser.add_argument('--hours', type=float, default=8)
parser.add_argument('--lal-status', default='http://127.0.0.1:8083/api/stat/all_group')
parser.add_argument('--out', default='artifacts/endurance.jsonl')
args = parser.parse_args()
path = pathlib.Path(args.out)
path.parent.mkdir(parents=True, exist_ok=True)
start = time.monotonic()

def adb(*command):
    return subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, text=True, timeout=20).stdout

with path.open('w') as output:
    while True:
        elapsed = time.monotonic() - start
        memory = adb('shell', 'dumpsys', 'meminfo', 'com.wusui.rtmpcapture')
        match = re.search(r'TOTAL PSS:\s*(\d+)', memory) or re.search(r'^\s*TOTAL\s+(\d+)', memory, re.M)
        row = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'elapsed_s': round(elapsed),
               'pid': adb('shell', 'pidof', 'com.wusui.rtmpcapture').strip(), 'pss_kib': int(match[1]) if match else None}
        try:
            with urllib.request.urlopen(args.lal_status, timeout=5) as response:
                data = json.load(response)
            # Drop publisher URL parameters, which may include lal_secret.
            groups = data.get('data', {}).get('groups') or []
            row['groups'] = [{'stream_name': g.get('stream_name'), 'publisher_alive': bool(g.get('pub')), 'publisher_bitrate_kbits': (g.get('pub') or {}).get('bitrate_kbits')} for g in groups]
        except Exception:
            row['status_error'] = True
        output.write(json.dumps(row, ensure_ascii=False) + '\n')
        output.flush()
        if elapsed >= args.hours * 3600:
            break
        time.sleep(min(60, args.hours * 3600 - elapsed))
print(path)
