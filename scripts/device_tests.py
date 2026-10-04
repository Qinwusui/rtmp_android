#!/usr/bin/env python3
"""Install this project's APKs and run instrumentation on an explicitly selected real device."""
import argparse, pathlib, re, subprocess, time, xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default='adb')
parser.add_argument('--variant', choices=['debug','release'], default='debug', help='Instrumentation runs on Debug; Release supports --install-only for actual UI validation.')
parser.add_argument('--install-only', action='store_true')
parser.add_argument('--local-backend', action='store_true')
parser.add_argument('--class-filter')
parser.add_argument('--out', default='artifacts/instrumentation.txt')
args=parser.parse_args()
if args.variant=='release' and not args.install_only: parser.error('Use --install-only for Release; instrumentation uses the Debug APK.')
root=pathlib.Path(__file__).resolve().parent.parent
base=[args.adb,'-s',args.serial]

apks=[root/f'app/build/outputs/apk/{args.variant}/app-{args.variant}.apk']
if not args.install_only: apks.append(root/f'app/build/outputs/apk/androidTest/{args.variant}/app-{args.variant}-androidTest.apk')
for apk in apks:
    if not apk.is_file(): raise SystemExit(f'Missing built APK: {apk}')
    proc=subprocess.Popen(base+['install','-r',str(apk)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    deadline=time.monotonic()+45
    while proc.poll() is None:
        if time.monotonic()>deadline:
            proc.terminate()
            raise SystemExit('Installation timed out; unlock the selected device and approve USB installation.')
        subprocess.run(base+['shell','uiautomator','dump','/sdcard/rtmp-install.xml'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=12)
        xml=subprocess.run(base+['shell','cat','/sdcard/rtmp-install.xml'],capture_output=True,text=True).stdout
        try:
            nodes=list(ET.fromstring(xml).iter('node'))
            # Only accept the MIUI installer prompt for this project's exact package names.
            ours=any(n.get('text') in ('屏幕摄像合流','com.wusui.rtmpcapture.test','com.wusui.rtmpcapture') for n in nodes)
            if ours:
                for node in nodes:
                    if node.get('text')=='继续安装' and node.get('package')=='com.miui.securitycenter':
                        box=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                        subprocess.run(base+['shell','input','tap',str((box[0]+box[2])//2),str((box[1]+box[3])//2)],check=True)
        except ET.ParseError: pass
        time.sleep(.5)
    output=proc.communicate()[0]
    print(output.strip(),flush=True)
    if proc.returncode: raise SystemExit(proc.returncode)

if args.install_only: raise SystemExit(0)
command=base+['shell','am','instrument','-w']
if args.local_backend: command+=['-e','localBackend','true']
if args.class_filter: command+=['-e','class',args.class_filter]
command+=['com.wusui.rtmpcapture.test/androidx.test.runner.AndroidJUnitRunner']
result=subprocess.run(command,capture_output=True,text=True,timeout=180)
path=pathlib.Path(args.out); path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(result.stdout+result.stderr)
print(result.stdout,flush=True)
if result.returncode or 'OK (' not in result.stdout or 'FAILURES' in result.stdout: raise SystemExit(1)
