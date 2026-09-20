"""Exercise onboarding on a disposable emulator; clears only ThumbTrek Dev data."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', default='emulator-5554')
args = parser.parse_args()
PACKAGE = 'com.thumbtrek.app.dev'
OUT = pathlib.Path(__file__).resolve().parents[1] / 'app/build/onboarding-review'
OUT.mkdir(parents=True, exist_ok=True)


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], timeout=30)


assert args.serial.startswith('emulator-'), 'Never reset a physical phone'
assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', 'Requires an emulator'


def launch():
    adb('shell', 'am', 'start', '-n', PACKAGE + '/com.thumbtrek.app.MainActivity')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        ready = subprocess.run(
            [args.adb, '-s', args.serial, 'shell', 'run-as', PACKAGE, 'test', '-f',
             'shared_prefs/thumbtrek_prefs.xml'], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=10,
        )
        if ready.returncode == 0:
            time.sleep(1)
            return
        time.sleep(.5)
    raise AssertionError('App did not initialize preferences within 30 seconds')


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/trek-setup.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/trek-setup.xml'))


def text():
    return ' '.join(n.get('text', '') for n in tree().iter('node'))


def tap_node(node):
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.5)


def tap(label):
    for _ in range(6):
        for n in tree().iter('node'):
            if n.get('text') == label:
                tap_node(n)
                return
        adb('shell', 'input', 'swipe', '500', '1750', '500', '700', '350')
    raise AssertionError('Missing control: ' + label)


def prefs():
    root = ET.fromstring(adb('shell','run-as',PACKAGE,'cat','shared_prefs/thumbtrek_prefs.xml'))
    return {n.get('name'): n.get('value') if n.get('value') is not None else (n.text or '') for n in root}


def shot(name):
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def restart():
    adb('shell','am','force-stop',PACKAGE)
    launch()


adb('shell', 'pm', 'clear', PACKAGE)
launch()
assert prefs()['onboarding_step'] == '0'
assert 'daily_limit_m' not in prefs()
assert 'Get started' in text(), 'No unsolicited permission dialog'
shot('welcome')
tap('Get started')
tap('Instagram')
assert 'com.instagram.android' not in adb('shell','run-as',PACKAGE,'cat','shared_prefs/thumbtrek_prefs.xml').decode()
tap('Open Accessibility settings')
assert b'com.android.settings' in adb('shell','dumpsys','activity','activities')
adb('shell','input','keyevent','4')
tap('Continue for now')
assert prefs()['onboarding_step'] == '2'
shot('limit-empty')
field = next(n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText')
tap_node(field)
adb('shell','input','text','250')
adb('shell','input','keyevent','4')
restart()
assert prefs()['onboarding_step'] == '2'
assert '250' in text(), 'Draft must survive process death'
shot('limit-custom')
tap('Use my limit')
assert float(prefs()['daily_limit_m']) == 250
assert prefs()['onboarding_step'] == '3'
shot('account')
tap('Continue without an account')
assert prefs()['onboarding_step'] == '4'
assert prefs().get('leaderboard_opt_in', 'false') == 'false'
restart()
assert 'Today' in text()
print('PASS custom limit, app selection, permission return, draft resume, optional account', flush=True)

tap('Settings')
tap('Review setup')
tap('Get started')
tap('Continue for now')
tap('Learn my baseline first')
tap('Continue without an account')
assert 'daily_limit_m' not in prefs()
assert prefs().get('limit_nudge') == 'false'
restart()
assert 'daily_limit_m' not in prefs(), 'No default may reappear'
assert 'Find your baseline' in text()
assert 'remaining today' not in text()
shot('baseline-dashboard')
tap('Settings')
# Scroll to the limit editor and verify it has no implicit value.
for _ in range(5):
    nodes = list(tree().iter('node'))
    fields = [n for n in nodes if n.get('class') == 'android.widget.EditText']
    if fields:
        assert fields[0].get('text') in ('', 'Metres per day')
        break
    adb('shell','input','swipe','500','1750','500','700','350')
else:
    raise AssertionError('No limit editor in Settings')
shot('baseline-settings')
print('PASS replay setup, baseline persistence, no assumed cap or nudges', flush=True)

# Simulate a pre-onboarding installation with explicit settings. No phone is touched.
adb('shell','am','force-stop',PACKAGE)
fixture = OUT / 'legacy-prefs.xml'
fixture.write_text('<map><float name="daily_limit_m" value="275.0"/><set name="tracked_apps"><string>com.reddit.frontpage</string></set></map>')
adb('push', str(fixture), '/data/local/tmp/trek-legacy-prefs.xml')
adb('shell','run-as',PACKAGE,'cp','/data/local/tmp/trek-legacy-prefs.xml','shared_prefs/thumbtrek_prefs.xml')
launch()
assert prefs()['onboarding_step'] == '4'
assert float(prefs()['daily_limit_m']) == 275
assert 'Today' in text()
assert 'com.reddit.frontpage' in adb('shell','run-as',PACKAGE,'cat','shared_prefs/thumbtrek_prefs.xml').decode()
print('PASS existing-install migration retains explicit settings', flush=True)
print('ONBOARDING CHECKS PASSED', flush=True)
