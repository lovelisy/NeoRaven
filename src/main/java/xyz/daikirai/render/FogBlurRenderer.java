package xyz.daikirai.render;

import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import xyz.daikirai.gui.onyx.material.Theme;
import xyz.daikirai.gui.onyx.render.Shader;
import xyz.daikirai.module.render.FogBlurModule;

public final class FogBlurRenderer {
    private static final int BLUR_UNIT = 2;
    private static final int DEPTH_UNIT = 3;
    private static final int SAVED_UNITS = 4;
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int ACTIVE_TEXTURE = 0x84E0;

    private final Minecraft mc = Minecraft.getMinecraft();
    private Shader down, up, composite;
    private int half = -1, quarter = -1, depth = -1, halfFbo = -1, quarterFbo = -1;
    private int width, height;
    private boolean failed;

    public void reset() { failed = false; }

    public void dispose() {
        if (halfFbo != -1) OpenGlHelper.glDeleteFramebuffers(halfFbo);
        if (quarterFbo != -1) OpenGlHelper.glDeleteFramebuffers(quarterFbo);
        for (int texture : new int[]{half, quarter, depth}) if (texture != -1) GL11.glDeleteTextures(texture);
        half = quarter = depth = halfFbo = quarterFbo = -1;
        width = height = 0;
    }

    public void render(FogBlurModule settings) {
        Framebuffer scene = mc.getFramebuffer();
        if (failed || !OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled() || scene == null) return;
        int previousFbo = GL11.glGetInteger(FRAMEBUFFER_BINDING);
        int previousMatrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int previousUnit = GL11.glGetInteger(ACTIVE_TEXTURE);
        TextureState previousTextures = captureTextures();
        boolean projectionPushed = false;
        try {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            projectionPushed = true;
            try (WorldRenderState state = new WorldRenderState(true)) {
                if (down == null) {
                    down = Shader.load("onyx_fog_down");
                    up = Shader.load("onyx_fog_up");
                    composite = Shader.load("onyx_fog_composite");
                }
                if (!down.valid() || !up.valid() || !composite.valid()) {
                    failed = true;
                    return;
                }
                ensure(scene.framebufferWidth, scene.framebufferHeight);
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
                copyDepth();
                setupProjection();
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                int hw = Math.max(1, width / 2);
                int hh = Math.max(1, height / 2);
                int qw = Math.max(1, hw / 2);
                int qh = Math.max(1, hh / 2);
                pass(down, scene.framebufferTexture, halfFbo, hw, hh, width, height);
                pass(down, half, quarterFbo, qw, qh, hw, hh);
                pass(up, quarter, halfFbo, hw, hh, qw, qh);
                compose(previousFbo, settings);
            }
        } catch (RuntimeException error) {
            failed = true;
            dispose();
            System.err.println("[onyx] Fog Blur disabled after render failure: " + error);
        } finally {
            if (projectionPushed) {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
            }
            GL11.glMatrixMode(previousMatrix);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFbo);
            restoreTextures(previousTextures);
            OpenGlHelper.setActiveTexture(previousUnit);
        }
    }

    private void copyDepth() {
        bind(BLUR_UNIT, depth);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
    }

    private static void setupProjection() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 1, 0, 1, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
    }

    private void compose(int target, FogBlurModule settings) {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target);
        GL11.glViewport(0, 0, width, height);
        composite.use();
        composite.uniform1i("BlurSampler", BLUR_UNIT);
        composite.uniform1i("DepthSampler", DEPTH_UNIT);
        composite.uniform4f("uCamera", .05F, Math.max(1.05F, mc.gameSettings.renderDistanceChunks * 64F), 1, 0);
        composite.uniform4f("uFog", (float) settings.distance.getInput(), (float) settings.fade.getInput(),
                (float) settings.tint.getInput(), settings.clientColor.isToggled() ? 1 : 0);
        composite.uniformColor("uColor1", Theme.scheme().primary);
        composite.uniformColor("uColor2", Theme.scheme().primary);
        composite.uniformColor("uColor3", Theme.scheme().tertiary);
        composite.uniformColor("uColor4", Theme.scheme().tertiary);
        bind(BLUR_UNIT, half);
        bind(DEPTH_UNIT, depth);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        quad();
    }

    private void ensure(int w, int h) {
        if (width == w && height == h && half != -1) return;
        dispose();
        half = texture(Math.max(1, w / 2), Math.max(1, h / 2), false);
        quarter = texture(Math.max(1, w / 4), Math.max(1, h / 4), false);
        depth = texture(w, h, true);
        halfFbo = framebuffer(half);
        quarterFbo = framebuffer(quarter);
        width = w;
        height = h;
    }

    private static int texture(int width, int height, boolean depth) {
        int texture = GL11.glGenTextures();
        bind(BLUR_UNIT, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depth ? 33190 : 32856, width, height, 0,
                depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, depth ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        return texture;
    }

    private static int framebuffer(int texture) {
        int fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        if (OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER) != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
            OpenGlHelper.glDeleteFramebuffers(fbo);
            throw new IllegalStateException("Incomplete fog framebuffer");
        }
        return fbo;
    }

    private static TextureState captureTextures() {
        TextureState state = new TextureState();
        for (int unit = 0; unit < SAVED_UNITS; unit++) {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + unit);
            state.textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            state.enabled[unit] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        }
        return state;
    }

    private static void restoreTextures(TextureState state) {
        for (int unit = 0; unit < SAVED_UNITS; unit++) {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.textures[unit]);
            if (state.enabled[unit]) GL11.glEnable(GL11.GL_TEXTURE_2D);
            else GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
    }

    private static final class TextureState {
        final int[] textures = new int[SAVED_UNITS];
        final boolean[] enabled = new boolean[SAVED_UNITS];
    }

    private static void bind(int unit, int texture) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void pass(Shader shader, int texture, int fbo, int w, int h, int sourceW, int sourceH) {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, w, h);
        bind(BLUR_UNIT, texture);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        shader.use();
        shader.uniform1i("Sampler0", BLUR_UNIT);
        shader.uniform2f("uSourceSize", sourceW, sourceH);
        quad();
    }

    private static void quad() {
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(0, 0);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, 0);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(0, 1);
        GL11.glEnd();
    }
}
