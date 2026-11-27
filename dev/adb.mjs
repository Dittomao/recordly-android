/**
 * Small client for an existing local ADB server. Useful on managed Windows
 * hosts where adb.exe cannot initialize its per-user directory.
 *
 * node dev/adb.mjs devices
 * node dev/adb.mjs shell <command> [args...]
 * node dev/adb.mjs exec-out <output-file> <command> [args...]
 * node dev/adb.mjs push <local-file> <device-path>
 * node dev/adb.mjs install <apk>
 * node dev/adb.mjs forward <local-port> <device-socket>
 */
import net from 'node:net';
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const quote = value => `'${String(value).replaceAll("'", "'\\''")}'`;

class Connection {
    constructor(socket) {
        this.socket = socket;
        this.buffer = Buffer.alloc(0);
        this.ended = false;
        this.error = null;
        this.wake = null;
        socket.on('data', data => {
            this.buffer = Buffer.concat([this.buffer, data]);
            this.wake?.();
        });
        socket.on('end', () => { this.ended = true; this.wake?.(); });
        socket.on('error', error => { this.error = error; this.wake?.(); });
        socket.setTimeout(120_000, () => socket.destroy(new Error('ADB connection timed out')));
    }
    async read(count) {
        while (this.buffer.length < count) {
            if (this.error) throw this.error;
            if (this.ended) throw new Error('ADB connection closed early');
            await new Promise(resolve => { this.wake = resolve; });
            this.wake = null;
        }
        const data = this.buffer.subarray(0, count);
        this.buffer = this.buffer.subarray(count);
        return data;
    }
    async all() {
        while (!this.ended) {
            if (this.error) throw this.error;
            await new Promise(resolve => { this.wake = resolve; });
            this.wake = null;
        }
        return this.buffer;
    }
    async command(service) {
        const bytes = Buffer.from(service);
        this.socket.write(Buffer.from(bytes.length.toString(16).padStart(4, '0')));
        this.socket.write(bytes);
        const status = (await this.read(4)).toString();
        if (status === 'FAIL') {
            const count = parseInt((await this.read(4)).toString(), 16);
            throw new Error((await this.read(count)).toString());
        }
        if (status !== 'OKAY') throw new Error(`Unexpected ADB status: ${status}`);
    }
    close() { this.socket.destroy(); }
}

async function connect() {
    const connection = new Connection(net.createConnection({ host: '127.0.0.1', port: 5037 }));
    return connection;
}

export async function devices() {
    const connection = await connect();
    try {
        await connection.command('host:devices-l');
        const count = parseInt((await connection.read(4)).toString(), 16);
        return (await connection.read(count)).toString();
    } finally { connection.close(); }
}

async function transport() {
    const connection = await connect();
    try {
        const serial = process.env.RECORDLY_DEVICE || 'emulator-5554';
        await connection.command(`host:transport:${serial}`);
        return connection;
    } catch (error) { connection.close(); throw error; }
}

/**
 * Resolve the app's own process by exact process name.
 *
 * `pidof <package>` also matches helper processes whose command line carries
 * the package (media.swcodec does on OnePlus), which sends verifiers to a
 * WebView socket that does not exist.
 */
export async function appProcessId(packageName) {
    const result = await shell(['ps', '-A', '-o', 'PID,NAME']);
    for (const line of result.stdout.split(/\r?\n/)) {
        const [pid, name] = line.trim().split(/\s+/);
        if (name === packageName) return pid;
    }
    return '';
}

export async function shell(args) {
    const connection = await transport();
    const stdout = [], stderr = [];
    try {
        await connection.command(`shell,v2,raw:${args.map(quote).join(' ')}`);
        while (true) {
            const header = await connection.read(5);
            const data = await connection.read(header.readUInt32LE(1));
            if (header[0] === 1) stdout.push(data);
            if (header[0] === 2) stderr.push(data);
            if (header[0] === 3) return {
                stdout: Buffer.concat(stdout).toString(),
                stderr: Buffer.concat(stderr).toString(),
                code: data[0],
            };
        }
    } finally { connection.close(); }
}

export async function execOut(args) {
    const connection = await transport();
    try {
        await connection.command(`exec:${args.map(quote).join(' ')}`);
        return await connection.all();
    } finally { connection.close(); }
}

function syncPacket(id, value) {
    const packet = Buffer.alloc(8);
    packet.write(id, 0, 'ascii');
    packet.writeUInt32LE(value, 4);
    return packet;
}

export async function push(local, remote) {
    const connection = await transport();
    try {
        const bytes = await readFile(local);
        await connection.command('sync:');
        const name = Buffer.from(`${remote},33206`);
        connection.socket.write(Buffer.concat([syncPacket('SEND', name.length), name]));
        for (let offset = 0; offset < bytes.length; offset += 64 * 1024) {
            const chunk = bytes.subarray(offset, offset + 64 * 1024);
            connection.socket.write(Buffer.concat([syncPacket('DATA', chunk.length), chunk]));
        }
        connection.socket.write(syncPacket('DONE', Math.floor(Date.now() / 1000)));
        const response = await connection.read(8);
        if (response.toString('ascii', 0, 4) !== 'OKAY') {
            throw new Error((await connection.read(response.readUInt32LE(4))).toString());
        }
    } finally { connection.close(); }
}

export async function forward(localPort, remoteSocket) {
    const connection = await connect();
    try {
        const serial = process.env.RECORDLY_DEVICE || 'emulator-5554';
        await connection.command(`host-serial:${serial}:forward:tcp:${localPort};${remoteSocket}`);
        await connection.all();
    } finally { connection.close(); }
}

async function main([action, ...args]) {
    switch (action) {
        case 'devices': process.stdout.write(await devices()); break;
        case 'shell': {
            const result = await shell(args);
            process.stdout.write(result.stdout);
            process.stderr.write(result.stderr);
            process.exitCode = result.code;
            break;
        }
        case 'exec-out': await writeFile(args[0], await execOut(args.slice(1))); break;
        case 'push': await push(args[0], args[1]); break;
        case 'install': {
            const destination = '/data/local/tmp/recordly-debug.apk';
            await push(args[0], destination);
            const result = await shell(['pm', 'install', '-r', destination]);
            process.stdout.write(result.stdout);
            process.stderr.write(result.stderr);
            process.exitCode = result.code;
            break;
        }
        case 'forward': await forward(args[0], args[1]); break;
        default: throw new Error('Expected devices, shell, exec-out, push, install, or forward');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
