#!/usr/bin/env python3
"""Static source/XML checks only; not aapt2, Android lint, type checking or APK build."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
APP = '{http://schemas.android.com/apk/res-auto}'

def require(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)
    print('PASS ' + label)

xmls = sorted((ROOT/'app/src/main').rglob('*.xml'))
for file in xmls:
    ET.parse(file)
require(bool(xmls), f'{len(xmls)} Android XML files are well-formed')
manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
application = manifest.find('application')
assert application is not None
for name in [application.get(A+'name')] + [x.get(A+'name') for x in application.findall('activity')]:
    assert name
    full = 'dev.flowstream.player'+name if name.startswith('.') else name
    require((ROOT/'app/src/main/java'/Path(full.replace('.', '/')+'.java')).is_file(), 'entry class exists: '+full)
require(application.get(A+'allowBackup') == 'false', 'backup remains disabled')
permissions = {x.get(A+'name') for x in manifest.findall('uses-permission')}
require('android.permission.SYSTEM_ALERT_WINDOW' not in permissions, 'no overlay permission added')
layout = ET.parse(ROOT/'app/src/main/res/layout/flow_video.xml').getroot()
require(layout.tag == 'androidx.media3.ui.PlayerView', 'video layout uses Media3 PlayerView')
require(layout.get(APP+'surface_type') == 'texture_view', 'video layout explicitly selects TextureView')
require(layout.get(APP+'resize_mode') == 'fill', 'video layout delegates aspect/FIT to texture matrix exactly once')
require(layout.get(APP+'use_controller') == 'false', 'built-in controller is disabled')
activity = (ROOT/'app/src/main/java/dev/flowstream/player/ui/PlayerActivity.java').read_text()
require('inflate(R.layout.flow_video,root,false)' in activity, 'Activity inflates the texture-backed video layout')
require('gestures.setOnClickListener(v->togglePlayback())' in activity, 'confirmed clicks are wired to pause/resume')
require('new VideoTransformController(video)' in activity, 'Activity connects video transform controller')
require('Player.REPEAT_MODE_ONE' in activity, 'single-video repeat remains enabled')
build = (ROOT/'app/build.gradle').read_text()
require("versionName '0.2.0'" in build and 'versionCode 2' in build, 'Android version is 0.2.0 / 2')
require("applicationId 'dev.flowstream.player'" in build, 'application ID is unchanged')
require(not list(ROOT.rglob('*.apk')), 'no unbuilt APK is bundled')
print('Static checks only: Android type/API/resource/device validation NOT performed.')
