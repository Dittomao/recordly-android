import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('./android.js', import.meta.url), 'utf8');
const microphone = {
    deviceId: 'default', kind: 'audioinput', label: 'Default microphone', groupId: 'android-default',
};

function adapter(enumerateDevices, nativeDevices = [microphone]) {
    const mediaDevices = { enumerateDevices };
    const window = {
        navigator: { mediaDevices },
        __recordlyHost: {
            getSync: () => JSON.stringify(nativeDevices), invoke() {}, subscribe() {},
        },
    };
    vm.runInNewContext(source, { window });
    return mediaDevices;
}

test('Android exposes its native microphone without requesting browser label permission', async () => {
    const camera = { deviceId: 'camera-1', kind: 'videoinput', label: 'Camera' };
    const devices = adapter(async () => [
        { deviceId: '', kind: 'audioinput', label: '' }, camera,
    ]);
    const result = await devices.enumerateDevices();
    assert.equal(result[0], camera);
    assert.deepEqual(JSON.parse(JSON.stringify(result[1])), microphone);
    assert.deepEqual(JSON.parse(JSON.stringify(result[1].toJSON())), microphone);
});

test('native microphone remains selectable when browser enumeration fails', async () => {
    const devices = adapter(async () => { throw new Error('WebView permission denied'); });
    assert.deepEqual(JSON.parse(JSON.stringify(await devices.enumerateDevices())), [microphone]);
});

test('devices without a native microphone keep browser enumeration', async () => {
    const enumerate = async () => [];
    assert.equal(adapter(enumerate, []).enumerateDevices, enumerate);
});
