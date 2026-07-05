package com.github.squi2rel.vp;

import com.github.squi2rel.vp.provider.MediaOption;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.IVideoPlayer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class VideoControlScreen extends Screen {
    private final ClientVideoScreen target;
    private TextFieldWidget urlField;
    private ButtonWidget playButton;
    private final List<MediaOption> options = new ArrayList<>();
    private String optionsUrl = "";
    private String urlText = "";
    private String status = "";
    private int optionPage = 0;
    private int selectedQueueIndex = -1;
    private long lastSeekSent;
    private ProgressSlider progressSlider;

    public VideoControlScreen(ClientVideoScreen target) {
        super(Text.literal("VideoPlayer"));
        this.target = target.getScreen();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(640, width - 40);
        int left = (width - panelWidth) / 2;
        int y = Math.max(24, height / 2 - 144);
        int controlsWidth = 360;
        int queueLeft = left + controlsWidth + 14;
        int queueWidth = panelWidth - controlsWidth - 14;
        int controlY = y + 28;

        urlField = new TextFieldWidget(textRenderer, left, controlY + 28, controlsWidth, 20, Text.literal("URL"));
        urlField.setMaxLength(1024);
        urlField.setPlaceholder(Text.literal("输入视频 URL"));
        urlField.setText(urlText);
        addDrawableChild(urlField);
        setInitialFocus(urlField);

        playButton = addDrawableChild(ButtonWidget.builder(Text.literal("默认播放"), button -> play(""))
                .dimensions(left, controlY + 56, 86, 20)
                .build());
        playButton.active = !urlText.isBlank();
        urlField.setChangedListener(value -> {
            urlText = value;
            if (playButton != null) playButton.active = !value.isBlank();
        });
        addDrawableChild(ButtonWidget.builder(Text.literal("解析画质"), button -> queryOptions())
                .dimensions(left + 92, controlY + 56, 86, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("同步"), button -> ClientPacketHandler.sync(target))
                .dimensions(left + 184, controlY + 56, 86, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("投票跳过"), button -> ClientPacketHandler.skip(target, false))
                .dimensions(left + 276, controlY + 56, 84, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("强制跳过"), button -> ClientPacketHandler.skip(target, true))
                .dimensions(left, controlY + 82, 112, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("停止本地"), button -> {
                    if (target.player != null) target.player.stop();
                })
                .dimensions(left + 118, controlY + 82, 112, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
                .dimensions(left + controlsWidth - 80, controlY + 82, 80, 20)
                .build());
        addDrawableChild(new SliderWidget(left, controlY + 108, controlsWidth, 20, Text.empty(), VideoPlayerClient.config.volume / 100.0) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Text.literal("音量: " + Math.round(value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                VideoPlayerClient.applyClientVolume((int) Math.round(value * 100));
            }
        });
        addDrawableChild(new SliderWidget(left, controlY + 132, controlsWidth, 20, Text.empty(), VideoPlayerClient.config.brightness / 100.0) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Text.literal("亮度: " + Math.round(value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                VideoPlayerClient.applyClientBrightness((int) Math.round(value * 100));
            }
        });
        progressSlider = addDrawableChild(new ProgressSlider(left, controlY + 156, controlsWidth));
        addOptionButtons(left, controlY + 182, controlsWidth);
        if (VideoPlayerClient.canManageQueue) {
            addQueueButtons(queueLeft, y + 214, queueWidth);
        }
    }

    private void queryOptions() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        urlText = url;
        options.clear();
        optionsUrl = url;
        if (shouldUseServerProvider()) {
            status = "正在向服务器查询可选画质...";
            ClientPacketHandler.queryOptions(target, url);
        } else {
            status = "未连接到 VideoPlayer Core";
        }
        rebuild();
    }

    private void play(String optionId) {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        urlText = url;
        if (shouldUseServerProvider()) {
            ClientPacketHandler.request(target, url, optionId);
            status = optionId == null || optionId.isBlank() ? "已提交默认播放" : "已提交画质: " + optionId;
            rebuild();
        } else {
            status = "未连接到 VideoPlayer Core";
            rebuild();
        }
    }

    private static boolean shouldUseServerProvider() {
        return VideoPlayerClient.hasCoreConnection();
    }

    private void addOptionButtons(int left, int y, int panelWidth) {
        int buttonWidth = (panelWidth - 8) / 2;
        int perPage = 6;
        int start = optionPage * perPage;
        int end = Math.min(options.size(), start + perPage);
        for (int i = start; i < end; i++) {
            MediaOption option = options.get(i);
            int local = i - start;
            int col = local % 2;
            int row = local / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal(option.label()), button -> play(option.id()))
                    .dimensions(left + col * (buttonWidth + 8), y + row * 22, buttonWidth, 20)
                    .build());
        }
        if (options.size() > perPage) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
                        optionPage = Math.max(0, optionPage - 1);
                        rebuild();
                    })
                    .dimensions(left, y + 68, 36, 20)
                    .build());
            int pages = (options.size() + perPage - 1) / perPage;
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
                        optionPage = Math.min(pages - 1, optionPage + 1);
                        rebuild();
                    })
                    .dimensions(left + 42, y + 68, 36, 20)
                    .build());
        }
    }

    private void addQueueButtons(int left, int y, int width) {
        int buttonWidth = (width - 6) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("置顶"), button -> queueAction(0))
                .dimensions(left, y, buttonWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("上移"), button -> queueAction(1))
                .dimensions(left + buttonWidth + 6, y, buttonWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("下移"), button -> queueAction(2))
                .dimensions(left, y + 24, buttonWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("删除"), button -> queueAction(3))
                .dimensions(left + buttonWidth + 6, y + 24, buttonWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("清空队列"), button -> {
                    ClientPacketHandler.queueAction(target, 4, -1);
                    selectedQueueIndex = -1;
                    status = "已请求清空队列";
                })
                .dimensions(left, y + 52, width, 20)
                .build());
    }

    private void queueAction(int action) {
        if (selectedQueueIndex < 0 || selectedQueueIndex >= target.infos.size()) {
            status = "请先在右侧队列中右键选择一项";
            return;
        }
        ClientPacketHandler.queueAction(target, action, selectedQueueIndex);
        status = switch (action) {
            case 0 -> "已请求置顶队列项";
            case 1 -> "已请求上移队列项";
            case 2 -> "已请求下移队列项";
            case 3 -> "已请求删除队列项";
            default -> "已提交队列操作";
        };
    }

    private void rebuild() {
        String text = urlField == null ? urlText : urlField.getText();
        clearChildren();
        urlText = text;
        init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int panelWidth = Math.min(640, width - 40);
        int left = (width - panelWidth) / 2;
        int y = Math.max(24, height / 2 - 144);
        int controlsWidth = 360;
        int queueLeft = left + controlsWidth + 14;
        int queueWidth = panelWidth - controlsWidth - 14;

        context.fill(left - 12, y - 18, left + panelWidth + 12, y + 328, 0xCC101014);
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, y - 10, color(0xFFFFFF));
        context.drawTextWithShadow(textRenderer, Text.literal("观影区: " + target.area.name + " / 屏幕: " + target.name), left, y + 8, color(0xD8D8D8));
        drawStatus(context, left, y + 30, controlsWidth);
        drawOptions(context, left, y + 296, controlsWidth);
        drawQueue(context, queueLeft, y + 28, queueWidth);
        if (progressSlider != null) progressSlider.refreshFromPlayer();
    }

    private void drawOptions(DrawContext context, int left, int y, int width) {
        if (!status.isBlank()) {
            context.drawTextWithShadow(textRenderer, Text.literal(status).formatted(Formatting.GRAY), left, y, color(0xA0A0A0));
        }
        if (!options.isEmpty()) {
            int pages = (options.size() + 5) / 6;
            String page = pages > 1 ? " 第 " + (optionPage + 1) + "/" + pages + " 页" : "";
            context.drawTextWithShadow(textRenderer, Text.literal("可选画质来自服务器当前账号态" + page).formatted(Formatting.GRAY), left, y + 12, color(0xA0A0A0));
        }
    }

    private void drawStatus(DrawContext context, int left, int y, int width) {
        IVideoPlayer player = target.player;
        VideoInfo current = target.infos.peek();
        String title = current == null ? "无正在播放视频" : current.name();
        context.drawTextWithShadow(textRenderer, Text.literal("当前: " + fit(title, width - 36)), left, y, color(0xFFFFFF));

        String progress = "进度: --";
        if (player != null) {
            long local = Math.max(0, player.getProgress());
            long total = player.getTotalProgress();
            progress = total > 0
                    ? "进度: " + formatDuration(local) + " / " + formatDuration(total)
                    : "进度: " + formatDuration(local) + " / LIVE";
        }
        context.drawTextWithShadow(textRenderer, Text.literal(progress), left, y + 12, color(0xC8FFC8));

    }

    private void drawQueue(DrawContext context, int left, int y, int width) {
        context.drawTextWithShadow(textRenderer, Text.literal("队列列表").formatted(Formatting.GOLD), left, y - 14, color(0xFFD080));
        List<VideoInfo> queue = new ArrayList<>(target.infos);
        int visible = Math.min(queue.size(), 8);
        if (queue.isEmpty()) {
            context.fill(left, y, left + width, y + 20, 0x6618181C);
            context.drawTextWithShadow(textRenderer, Text.literal("空队列"), left + 6, y + 6, color(0xA0A0A0));
        }
        for (int i = 0; i < visible; i++) {
            VideoInfo info = queue.get(i);
            int rowY = y + i * 22;
            int color = i == selectedQueueIndex ? 0xAA486070 : (i == 0 ? 0x88405240 : 0x6618181C);
            context.fill(left, rowY, left + width, rowY + 20, color);
            String prefix = i == 0 ? "播放中 " : (i + 1) + ". ";
            context.drawTextWithShadow(textRenderer, Text.literal(fit(prefix + info.name(), width - 12)), left + 6, rowY + 6, color(0xFFFFFF));
        }
        if (queue.size() > visible) {
            context.drawTextWithShadow(textRenderer, Text.literal("还有 " + (queue.size() - visible) + " 项未显示").formatted(Formatting.GRAY), left, y + visible * 22 + 4, color(0xA0A0A0));
        }
        if (selectedQueueIndex >= 0 && VideoPlayerClient.canManageQueue) {
            context.drawTextWithShadow(textRenderer, Text.literal("已选中第 " + (selectedQueueIndex + 1) + " 项").formatted(Formatting.AQUA), left, y + 274, color(0x80FFFF));
        } else if (VideoPlayerClient.canManageQueue) {
            context.drawTextWithShadow(textRenderer, Text.literal("左键立即播放，右键选中后可置顶/移动/删除").formatted(Formatting.GRAY), left, y + 274, color(0xA0A0A0));
        } else {
            context.drawTextWithShadow(textRenderer, Text.literal("左键立即播放").formatted(Formatting.GRAY), left, y + 274, color(0xA0A0A0));
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0 || click.button() == 1) {
            int panelWidth = Math.min(640, width - 40);
            int left = (width - panelWidth) / 2;
            int y = Math.max(24, height / 2 - 144);
            int controlsWidth = 360;
            int queueLeft = left + controlsWidth + 14;
            int queueWidth = panelWidth - controlsWidth - 14;
            int queueY = y + 28;
            int visible = Math.min(target.infos.size(), 8);
            for (int i = 0; i < visible; i++) {
                int rowY = queueY + i * 22;
                if (mouseX >= queueLeft && mouseX <= queueLeft + queueWidth && mouseY >= rowY && mouseY <= rowY + 20) {
                    if (click.button() == 0) {
                        if (i == 0) {
                            status = "该视频已在播放";
                        } else {
                            ClientPacketHandler.queueAction(target, 5, i);
                            selectedQueueIndex = -1;
                            status = "已请求播放队列第 " + (i + 1) + " 项";
                        }
                    } else if (VideoPlayerClient.canManageQueue) {
                        selectedQueueIndex = i;
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private String fit(String text, int width) {
        if (textRenderer.getWidth(text) <= width) return text;
        String ellipsis = "...";
        int max = Math.max(1, text.length() - 1);
        while (max > 1 && textRenderer.getWidth(text.substring(0, max) + ellipsis) > width) {
            max--;
        }
        return text.substring(0, max) + ellipsis;
    }

    private static int color(int rgb) {
        return 0xFF000000 | rgb;
    }

    private boolean canControlProgress() {
        IVideoPlayer player = target.player;
        return player != null && player.canSetProgress() && player.getTotalProgress() > 0;
    }

    private double progressSliderValue() {
        IVideoPlayer player = target.player;
        if (player == null) return 0;
        long total = player.getTotalProgress();
        if (total <= 0) return 0;
        long progress = Math.max(0, player.getProgress());
        return Math.clamp(progress / (double) total, 0.0, 1.0);
    }

    private void sendSeek(long progress, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSeekSent < 250) return;
        lastSeekSent = now;
        ClientPacketHandler.seek(target, progress);
    }

    private static long progressFromValue(double value, long total) {
        return total <= 0 ? 0 : Math.round(Math.clamp(value, 0.0, 1.0) * total);
    }

    private class ProgressSlider extends SliderWidget {
        ProgressSlider(int x, int y, int width) {
            super(x, y, width, 20, Text.empty(), progressSliderValue());
            active = canControlProgress();
            updateMessage();
        }

        void refreshFromPlayer() {
            active = canControlProgress();
            if (!isFocused() && !isHovered()) {
                value = progressSliderValue();
            }
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            IVideoPlayer player = target.player;
            long total = player == null ? -1 : player.getTotalProgress();
            long progress = total > 0 ? progressFromValue(value, total) : 0;
            setMessage(total > 0
                    ? Text.literal("进度: " + formatDuration(progress) + " / " + formatDuration(total))
                    : Text.literal("进度: --"));
        }

        @Override
        protected void applyValue() {
            if (!canControlProgress()) return;
            IVideoPlayer player = target.player;
            long progress = progressFromValue(value, player.getTotalProgress());
            target.setProgress(progress);
            sendSeek(progress, false);
            status = "已跳转到 " + formatDuration(progress);
        }
    }

    private static String formatDuration(long millis) {
        long all = Math.max(0, millis / 1000);
        long hours = all / 3600;
        long minutes = (all % 3600) / 60;
        long seconds = all % 60;
        return hours > 0 ? "%02d:%02d:%02d".formatted(hours, minutes, seconds) : "%02d:%02d".formatted(minutes, seconds);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static void open(ClientVideoScreen screen) {
        MinecraftClient.getInstance().setScreen(new VideoControlScreen(screen));
    }

    public static void refreshCurrent() {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().currentScreen instanceof VideoControlScreen control) {
                control.selectedQueueIndex = -1;
                control.rebuild();
            }
        });
    }

    public static void receiveOptions(ClientVideoScreen screen, String url, List<MediaOption> received) {
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().currentScreen instanceof VideoControlScreen control
                    && control.target.area.name.equals(screen.area.name)
                    && control.target.name.equals(screen.name)
                    && url.equals(control.optionsUrl)) {
                control.options.clear();
                control.options.addAll(received);
                control.optionPage = 0;
                control.status = received.isEmpty() ? "服务器未返回可选画质" : "已获取 " + received.size() + " 个可选画质";
                control.rebuild();
            }
        });
    }
}
