/** Generate a visual/audio sync fixture or measure its markers in a short MP4. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const ffmpeg = args => execFileSync('ffmpeg', [
    '-hide_banner', '-loglevel', 'error', ...args,
], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
const nullOutput = process.platform === 'win32' ? 'NUL' : '/dev/null';

function fixture(path) {
    ffmpeg([
        '-n', '-f', 'lavfi', '-i', 'color=c=0x202020:s=720x1280:r=30:d=45',
        '-f', 'lavfi', '-i', 'aevalsrc=0.25*sin(2*PI*880*t)*between(mod(t\\,3)\\,1\\,1.25):s=48000:d=45',
        '-vf', "drawbox=x=iw/4:y=ih/2-ih/10:w=iw/2:h=ih/5:color=0xb0b0b0:t=fill:enable='between(mod(t,3),1,1.25)'",
        '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-movflags', '+faststart', path,
    ]);
}

function samples(path, map, filter, key) {
    const metadata = ffmpeg([
        '-copyts', '-i', path, '-map', map, ...filter,
        '-f', 'null', nullOutput,
    ]);
    const lines = metadata.split(/\r?\n/);
    const points = [];
    let time = NaN;
    for (const line of lines) {
        const header = line.match(/\bpts_time:([-\d.e+]+)/i);
        if (header) time = Number(header[1]);
        if (Number.isFinite(time) && line.startsWith(`${key}=`)) {
            points.push({ time, value: Number(line.slice(key.length + 1)) });
        }
    }
    assert.ok(points.length > 0, `No samples returned for ${map}`);
    return points;
}

function onsets(points, threshold, step) {
    const result = [];
    let start = null;
    let last = null;
    const flush = () => {
        // Ignore one-frame flashes and long unrelated bright/noisy sections.
        if (start !== null && last - start + step >= 0.12 && last - start + step <= 0.42) {
            result.push(start);
        }
        start = last = null;
    };
    for (const { time, value } of points) {
        if (value < threshold || Number.isNaN(value)) {
            flush();
        } else {
            if (last !== null && time - last > 0.10) flush();
            start ??= time;
            last = time;
        }
    }
    flush();
    return result;
}

function measure(path) {
    const { streams, format } = JSON.parse(execFileSync('ffprobe', [
        '-v', 'error', '-show_entries', 'stream=codec_type,start_time,duration,bit_rate:format=duration',
        '-of', 'json', path,
    ], { encoding: 'utf8' }));
    assert.ok(Number(format.duration) <= 90, 'Use a test clip no longer than 90 seconds');
    const video = streams.find(stream => stream.codec_type === 'video');
    const audio = streams.find(stream => stream.codec_type === 'audio');
    assert.ok(video && audio, 'The take needs both video and microphone audio');

    const videoKey = 'lavfi.signalstats.YAVG';
    const audioKey = 'lavfi.astats.Overall.RMS_level';
    const luminance = samples(path, '0:v:0', ['-vf',
        `crop=iw/6:ih/12,signalstats,metadata=mode=print:key=${videoKey}:file=-`], videoKey);
    const signal = samples(path, '0:a:0', ['-af',
        `aresample=48000,bandpass=f=880:width_type=h:width=120,asetnsamples=n=480:p=0,astats=metadata=1:reset=1:measure_perchannel=none:measure_overall=RMS_level,ametadata=mode=print:key=${audioKey}:file=-`], audioKey);
    const levels = signal.map(point => point.value).sort((a, b) => a === b ? 0 : a - b);
    const audioThreshold = Math.max(-60, Math.min(-25, levels[Math.floor(levels.length * 0.96)] - 12));
    const flashes = onsets(luminance, 105, 1 / 30);
    const tones = onsets(signal, audioThreshold, 0.01);
    const matches = flashes.flatMap(flash => {
        // Markers are three seconds apart. A ±750 ms window can't pair one
        // flash with a neighboring fixture beep under normal playback.
        const nearby = tones.filter(tone => Math.abs(tone - flash) < 0.75);
        if (nearby.length !== 1) return [];
        return [{ videoTime: flash, audioTime: nearby[0], offsetMs: Math.round((nearby[0] - flash) * 1000) }];
    });
    const audioEnd = Number(audio.start_time) + Number(audio.duration);
    const videoEnd = Number(video.start_time) + Number(video.duration);
    const result = {
        audioDuration: Number(audio.duration), videoDuration: Number(video.duration),
        trackEndDeltaMs: Math.round((audioEnd - videoEnd) * 1000), audioBitrate: Number(audio.bit_rate),
        flashes, tones, audioThreshold, matches,
    };
    console.log(JSON.stringify(result, null, 2));
    assert.ok(matches.length >= 2, 'Inconclusive: fewer than two flash/tone pairs matched');
    assert.ok(matches.every(pair => Math.abs(pair.offsetMs) <= 250), 'A flash/tone pair is out of sync by more than 250 ms');
    assert.ok(Math.abs(result.trackEndDeltaMs) <= 250, 'Track end times diverged by more than 250 ms');
}

const [mode, path] = process.argv.slice(2);
assert.ok(path && (mode === 'fixture' || mode === 'measure'),
    'Usage: node dev/verify-av-sync.mjs fixture|measure <mp4>');
if (mode === 'fixture') fixture(path);
else measure(path);
