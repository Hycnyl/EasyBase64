#!/usr/bin/env python3
"""Small host helper for the recorded Android DocumentsUI smoke flow."""
import pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent

def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=90)

def window():
    for attempt in range(3):
        remote=f'/sdcard/easybase64-smoke-window-{time.monotonic_ns()}.xml'
        adb('shell','uiautomator','dump','--compressed',remote)
        raw=adb('exec-out','cat',remote)
        try: result=ET.fromstring(raw)
        except ET.ParseError:
            if attempt==2: raise
            time.sleep(1); continue
        (ROOT/'last-window.xml').write_bytes(raw)
        return result

def labels(root):
    for n in root.iter('node'):
        a=n.attrib
        if a.get('text') or a.get('content-desc'):
            print(a.get('text',''), '|', a.get('content-desc',''), '|',a.get('bounds'), '|',a.get('enabled'))

def tap(label, exact=True):
    root=window()
    for n in root.iter('node'):
        a=n.attrib
        choices=[a.get('text',''),a.get('content-desc','')]
        if any(v==label if exact else label.lower() in v.lower() for v in choices):
            x1,y1,x2,y2=map(int,re.findall(r'\d+',a['bounds']))
            if x1==x2 or y1==y2: continue
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            print('Tapped',label,a['bounds']);return
    labels(root)
    raise SystemExit('No visible target: '+label)

if __name__=='__main__':
    action=sys.argv[1] if len(sys.argv)>1 else 'dump'
    if action=='dump':labels(window())
    elif action=='tap':tap(sys.argv[2])
    elif action=='contains':tap(sys.argv[2],False)
    elif action=='wait':
        label=sys.argv[2]; deadline=time.monotonic()+90; root=None
        while time.monotonic()<deadline:
            try: root=window()
            except (ET.ParseError, subprocess.SubprocessError):
                time.sleep(1); continue
            if any(label in n.attrib.get('text','') or label in n.attrib.get('content-desc','') for n in root.iter('node')):
                print('Visible:',label);break
            time.sleep(1)
        else:
            if root is not None: labels(root)
            raise SystemExit('Timed out waiting: '+label)
