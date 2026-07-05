package com.github.squi2rel.vp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class VideoSetupPromptScreen extends Screen {
    private final boolean inArea;

    public VideoSetupPromptScreen(boolean inArea) {
        super(Text.literal("VideoPlayer-Client"));
        this.inArea = inArea;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(380, width - 40);
        int left = (width - panelWidth) / 2;
        int y = Math.max(48, height / 2 - 56);
        addDrawableChild(ButtonWidget.builder(Text.literal("查看创建命令"), button -> {
                    if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(Text.literal(
                                "请使用 /vlc-core createArea ... 和 /vlc-core createScreen ... 创建区域与屏幕"
                        ).formatted(Formatting.YELLOW), false);
                    }
                    close();
                })
                .dimensions(left, y + 72, panelWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
                .dimensions(left, y + 98, panelWidth, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int panelWidth = Math.min(380, width - 40);
        int left = (width - panelWidth) / 2;
        int y = Math.max(48, height / 2 - 56);
        context.fill(left - 12, y - 18, left + panelWidth + 12, y + 132, 0xCC101014);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y - 10, 0xFFFFFF);
        String message = inArea ? "当前观影区未存在可操作屏幕" : "当前位置未存在观影区或屏幕";
        context.drawTextWithShadow(textRenderer, Text.literal(message).formatted(Formatting.YELLOW), left, y + 16, 0xFFE080);
        context.drawTextWithShadow(textRenderer, Text.literal("是否需要新建区域并创建屏幕？").formatted(Formatting.GRAY), left, y + 32, 0xC8C8C8);
        context.drawTextWithShadow(textRenderer, Text.literal("创建与播放等核心功能现在由 /vlc-core 负责。").formatted(Formatting.GRAY), left, y + 48, 0xA0A0A0);
        super.render(context, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
