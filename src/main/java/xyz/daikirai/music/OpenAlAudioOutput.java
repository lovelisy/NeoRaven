package xyz.daikirai.music;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCcontext;

public final class OpenAlAudioOutput implements AudioOutput {

    private static final int BUFFERS = 5;

    private final ALCcontext context;
    private final int sampleRate;
    private final int format;
    private final int frameBytes;
    private final int chunkBytes;
    private final ByteBuffer pending;
    private final Object lock = new Object();

    private int source = -1;
    private final ArrayDeque<Integer> freeBuffers = new ArrayDeque<Integer>();
    private final ArrayDeque<Long> queuedFrames = new ArrayDeque<Long>();
    private int createdBuffers = 0;

    private volatile boolean running = true;
    private volatile boolean closed = false;

    private OpenAlAudioOutput(ALCcontext context, int sampleRate, int channels) {
        this.context = context;
        this.sampleRate = sampleRate;
        this.format = channels >= 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        this.frameBytes = 2 * (channels >= 2 ? 2 : 1);
        this.chunkBytes = Math.max(256, sampleRate / 20) * frameBytes;
        this.pending = BufferUtils.createByteBuffer(chunkBytes);
    }

    @Override
    public void setGain(float gain) {
        synchronized (lock) {
            if (!alive() || source == -1) {
                return;
            }
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0.0F, Math.min(1.0F, gain)));
            AL10.alGetError();
        }
    }

    @Override
    public boolean hasGain() {
        return true;
    }

    public static OpenAlAudioOutput open(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0) {
            return null;
        }
        ALCcontext current;
        try {
            current = ALC10.alcGetCurrentContext();
        } catch (Throwable t) {
            return null;
        }
        if (current == null || !current.isValid()) {
            return null;
        }
        AL10.alGetError();
        OpenAlAudioOutput out = new OpenAlAudioOutput(current, sampleRate, channels);
        try {
            out.source = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                out.close();
                return null;
            }
            AL10.alSourcei(out.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(out.source, AL10.AL_POSITION, 0.0F, 0.0F, 0.0F);
            AL10.alSourcef(out.source, AL10.AL_ROLLOFF_FACTOR, 0.0F);
            AL10.alSourcef(out.source, AL10.AL_GAIN, 1.0F);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                out.close();
                return null;
            }
        } catch (Throwable t) {
            out.close();
            return null;
        }
        return out;
    }

    private boolean contextAlive() {
        try {
            ALCcontext now = ALC10.alcGetCurrentContext();
            return now != null && now.isValid() && now.equals(context);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean alive() {
        return !closed && contextAlive();
    }

    @Override
    public boolean isLost() {
        return !closed && !contextAlive();
    }

    private void requireAlive() {
        if (!alive()) {
            throw new IllegalStateException("game audio restarted");
        }
    }

    @Override
    public void write(byte[] data, int offset, int length) {
        int off = offset;
        int left = length - length % frameBytes;
        while (left > 0) {
            if (closed) {
                return;
            }
            int n = Math.min(left, pending.remaining());
            pending.put(data, off, n);
            off += n;
            left -= n;
            if (!pending.hasRemaining()) {
                submit();
            }
        }
    }

    private void submit() {
        int buffer = takeBuffer();
        if (buffer < 0) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            requireAlive();
            pending.flip();
            long frames = pending.remaining() / frameBytes;
            AL10.alBufferData(buffer, format, pending, sampleRate);
            pending.clear();
            AL10.alSourceQueueBuffers(source, buffer);
            queuedFrames.addLast(frames);
            if (running && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                AL10.alSourcePlay(source);
            }
            AL10.alGetError();
        }
    }

    private int takeBuffer() {
        while (true) {
            synchronized (lock) {
                if (closed) {
                    return -1;
                }
                requireAlive();
                reclaim();
                Integer free = freeBuffers.pollFirst();
                if (free != null) {
                    return free;
                }
                if (createdBuffers < BUFFERS) {
                    createdBuffers++;
                    return AL10.alGenBuffers();
                }
            }
            sleep(5L);
        }
    }

    private void reclaim() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            int done = AL10.alSourceUnqueueBuffers(source);
            freeBuffers.addLast(done);
            queuedFrames.pollFirst();
        }
    }

    @Override
    public void drain() {
        if (pending.position() > 0) {
            submit();
        }
        while (true) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                requireAlive();
                reclaim();
                if (queuedFrames.isEmpty()) {
                    return;
                }
                if (running && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                    AL10.alSourcePlay(source);
                }
            }
            sleep(10L);
        }
    }

    @Override
    public void flush() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            pending.clear();
            if (!alive()) {
                return;
            }
            AL10.alSourceStop(source);
            reclaim();
            pending.clear();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            try {
                if (contextAlive()) {
                    AL10.alSourceStop(source);
                    AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                    if (source != -1) {
                        AL10.alDeleteSources(source);
                    }
                    for (int b : freeBuffers) {
                        AL10.alDeleteBuffers(b);
                    }
                    AL10.alGetError();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
