#!/usr/bin/env python3
"""Manifest, icon and settings validation for Reolink Doorbell plugin."""
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

manifest = json.loads((ROOT / 'kiosk-satellite-plugin.json').read_text())
assert manifest['id'] == 'reolink-doorbell' and manifest['name'] == 'Reolink Doorbell'
assert manifest['icon'] == 'icon.png', 'Icon path is relative to assets/'
icon = (ROOT / 'assets' / manifest['icon']).read_bytes()
assert icon[:8] == b'\x89PNG\r\n\x1a\n' and icon[12:16] == b'IHDR'
assert struct.unpack('>II', icon[16:24]) == (256, 256) and len(icon) <= 256 * 1024
keys = set()
for setting in manifest['settings']:
    assert setting['key'] not in keys
    keys.add(setting['key'])
    if setting['type'] == 'number':
        low, high, step, value = (setting[k] for k in ('min', 'max', 'step', 'default'))
        assert low <= value <= high and step > 0
        assert abs((high - low) / step - round((high - low) / step)) < 1e-6
        assert abs((value - low) / step - round((value - low) / step)) < 1e-6
    if setting['type'] == 'select':
        assert setting['default'] in setting['options']
    if setting['type'] == 'secret':
        assert setting['default'] == ''
assert next(s for s in manifest['settings'] if s['key'] == 'talkEnabled')['default'] is False
assert [command['id'] for command in manifest['commands']] == ['show']
assert manifest['minAndroidSdk'] == 26, 'AudioFocusRequest requires Android 8/API 26'
viewer_source = (ROOT / 'src/net/reolinkdoorbell/CameraView.java').read_text()
assert 'button("Close")' in viewer_source and 'button("Back")' not in viewer_source
assert 'theme.icon(name' in viewer_source and 'colorButton(talk, highlighted)' in viewer_source
runtime_sources = '\n'.join(p.read_text() for p in (ROOT / 'src').rglob('*.java'))
assert not any(s in runtime_sources for s in ('System.out.', 'System.err.', 'printStackTrace(', 'android.util.Log.'))
print('PASS: manifest defaults, numeric ranges/steps and secret defaults')
