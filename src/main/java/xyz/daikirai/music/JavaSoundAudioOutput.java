package xyz.daikirai.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public final class JavaSoundAudioOutput implements AudioOutput {

    private static final int LINE_BUFFER_FRAMES = 8192;

    private final SourceDataLine line;

    private JavaSoundAudioOutput(SourceDataLine line) {
        this.line = line;
    }

    public static JavaSoundAudioOutput open(int sampleRate, int channels) {
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try {
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, LINE_BUFFER_FRAMES * channels * 2);
            line.start();
            return new JavaSoundAudioOutput(line);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void write(byte[] data, int offset, int length) {
        line.write(data, offset, length);
    }

    @Override
    public void setGain(float gain) {
    }

    @Override
    public boolean hasGain() {
        return false;
    }

    @Override
    public void flush() {
        line.flush();
    }

    @Override
    public void drain() {
        line.drain();
    }

    @Override
    public void close() {
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isLost() {
        return !line.isOpen();
    }
}
