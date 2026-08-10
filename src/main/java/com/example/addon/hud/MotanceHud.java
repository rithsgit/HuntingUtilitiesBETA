package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.Tim;
import com.mojang.blaze3d.systems.RenderSystem;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

public class MotanceHud extends HudElement {
    public static final HudElementInfo<MotanceHud> INFO = new HudElementInfo<>(
        Tim.HUD_GROUP, "Motance", 
        "Motance",
        "Shows icons for sneaking, jumping, and sprinting.",
        MotanceHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private static final Identifier SPEED_TEXTURE = Identifier.of("minecraft", "textures/mob_effect/speed.png");
    private static final Identifier SLOWNESS_TEXTURE = Identifier.of("minecraft", "textures/mob_effect/slowness.png");
    private static final Identifier JUMP_TEXTURE = Identifier.of("minecraft", "textures/mob_effect/jump_boost.png");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ── Layout & Alignment ────────────────────────────────────────────────────

    public enum Layout { Inline, Stacked }

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("How the data is presented.")
        .defaultValue(Layout.Inline)
        .build()
    );

    public enum Alignment { Left, Center, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align icons to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    // ── Visual settings ───────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The overall scale of the element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the icons relative to the overall scale.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderRange(0.5, 4.0)
        .build()
    );

    private final Setting<Double> iconGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icons.")
        .defaultValue(4.0)
        .min(0)
        .sliderRange(0, 16)
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background behind the element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color of the background.")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    private final Setting<SettingColor> speedTint = sgGeneral.add(new ColorSetting.Builder()
        .name("speed-tint")
        .description("Color tint for the speed icon.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> slownessTint = sgGeneral.add(new ColorSetting.Builder()
        .name("slowness-tint")
        .description("Color tint for the slowness icon.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> jumpTint = sgGeneral.add(new ColorSetting.Builder()
        .name("jump-tint")
        .description("Color tint for the jump boost icon.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    public MotanceHud() { 
        super(INFO); 
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { 
            setSize(0, 0); 
            return; 
        }

        boolean isSprinting = mc.player.isSprinting();
        boolean isSneaking = mc.player.isSneaking();
        // Detect jumping by checking if the player is airborne and moving upwards
        boolean isJumping = !mc.player.isOnGround() && mc.player.getVelocity().y > 0.0;

        // If not moving and not in editor, don't take up HUD space
        if (!isSprinting && !isSneaking && !isJumping && !isInEditor()) {
            setSize(0, 0);
            return;
        }

        // Gather active icons in order: Sneak, Jump, Sprint
        List<Identifier> activeIcons = new ArrayList<>();
        if (isSneaking) activeIcons.add(SLOWNESS_TEXTURE);
        if (isJumping) activeIcons.add(JUMP_TEXTURE);
        if (isSprinting) activeIcons.add(SPEED_TEXTURE);

        // Show a preview in the editor so the user can position it
        if (isInEditor() && activeIcons.isEmpty()) {
            activeIcons.add(SLOWNESS_TEXTURE);
            activeIcons.add(JUMP_TEXTURE);
            activeIcons.add(SPEED_TEXTURE);
        }

        if (activeIcons.isEmpty()) {
            setSize(0, 0);
            return;
        }

        // Calculate dimensions based on layout
        double s = scale.get();
        double iconSize = 18.0 * iconScale.get() * s; // 18x18 is native texture size
        double padH = 4 * s;
        double padV = 4 * s;
        double gapSize = iconGap.get() * s;

        double contentW, contentH;
        if (layout.get() == Layout.Inline) {
            contentW = (activeIcons.size() * iconSize) + Math.max(0, activeIcons.size() - 1) * gapSize;
            contentH = iconSize;
        } else { // Stacked
            contentW = iconSize;
            contentH = (activeIcons.size() * iconSize) + Math.max(0, activeIcons.size() - 1) * gapSize;
        }

        double totalW = contentW + (padH * 2);
        double totalH = contentH + (padV * 2);

        setSize(totalW, totalH);

        if (showBackground.get()) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        // Initialize context once per render pass
        DrawContext context = new DrawContext(mc, mc.getBufferBuilders().getEntityVertexConsumers());

        double startX, startY;
        if (layout.get() == Layout.Inline) {
            if (alignment.get() == Alignment.Left)        startX = x + padH;
            else if (alignment.get() == Alignment.Center) startX = x + (totalW - contentW) / 2.0;
            else                                         startX = x + totalW - padH - contentW;
            
            startY = y + padV;

            double curX = startX;
            for (Identifier icon : activeIcons) {
                drawEffectIcon(context, icon, curX, startY, iconScale.get() * s, getColor(icon));
                curX += iconSize + gapSize;
            }
        } else { // Stacked
            if (alignment.get() == Alignment.Left)        startX = x + padH;
            else if (alignment.get() == Alignment.Center) startX = x + (totalW - iconSize) / 2.0;
            else                                         startX = x + totalW - padH - iconSize;
            
            startY = y + padV;

            double curY = startY;
            for (Identifier icon : activeIcons) {
                drawEffectIcon(context, icon, startX, curY, iconScale.get() * s, getColor(icon));
                curY += iconSize + gapSize;
            }
        }

        context.draw(); // Flush the buffer immediately after drawing all icons
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SettingColor getColor(Identifier texture) {
        if (texture.equals(SPEED_TEXTURE)) return speedTint.get();
        if (texture.equals(SLOWNESS_TEXTURE)) return slownessTint.get();
        if (texture.equals(JUMP_TEXTURE)) return jumpTint.get();
        return new SettingColor(255, 255, 255, 255);
    }

    /**
     * Draws standard Minecraft status effect textures using 1.21.4's DrawContext.
     */
    private void drawEffectIcon(DrawContext context, Identifier texture, double x, double y, double scale, SettingColor color) {
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale((float) scale, (float) scale, 1.0f);
        
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(color.r / 255.0f, color.g / 255.0f, color.b / 255.0f, color.a / 255.0f);
        
        // 1.21.4 requires a Function<Identifier, RenderLayer> as the first argument for drawTexture
        context.drawTexture(RenderLayer::getGuiTextured, texture, 0, 0, 0.0f, 0.0f, 18, 18, 18, 18);
        
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        
        context.getMatrices().pop();
    }
}