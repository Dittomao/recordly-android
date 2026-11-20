(async () => {
  const result = { video: {}, audio: {}, userAgent: navigator.userAgent, secure: isSecureContext, videoFrame: typeof VideoFrame };
  const sizes = [[1920, 1080], [1280, 720], [1080, 2400]];
  const codecs = ['avc1.42001f', 'avc1.4d401f', 'avc1.640033', 'vp8', 'vp09.00.10.08', 'av01.0.04M.08', 'hvc1.1.6.L93.B0'];
  for (const size of sizes) {
    for (const codec of codecs) {
      for (const accel of ['prefer-hardware', 'prefer-software', 'no-preference']) {
        const config = { codec, width: size[0], height: size[1], bitrate: 4_000_000, framerate: 30, hardwareAcceleration: accel };
        try {
          const support = await VideoEncoder.isConfigSupported(config);
          const key = size.join('x');
          result.video[key] ??= {};
          result.video[key][codec] ??= {};
          result.video[key][codec][accel] = support.supported;
        } catch (error) {
          result.video[size.join('x')] ??= {};
          result.video[size.join('x')][codec] ??= {};
          result.video[size.join('x')][codec][accel] = `error: ${error}`;
        }
      }
    }
  }
  for (const codec of ['mp4a.40.2', 'opus', 'vorbis']) {
    try {
      const support = await AudioEncoder.isConfigSupported({ codec, sampleRate: 48000, numberOfChannels: 2, bitrate: 128000 });
      result.audio[codec] = support.supported;
    } catch (error) {
      result.audio[codec] = `error: ${error}`;
    }
  }
  return result;
})()
