package dev.mcai.companion.client.modelsetup;

import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.agent.AgentAccentColor;
import dev.mcai.companion.agent.AgentNameRules;
import dev.mcai.companion.model.EndpointValidationException;
import dev.mcai.companion.model.EndpointValidator;
import dev.mcai.companion.modelsetup.network.ClientboundModelSetupState;
import dev.mcai.companion.modelsetup.network.ModelSetupNetwork;
import dev.mcai.companion.modelsetup.network.ModelSetupWireLimits;
import dev.mcai.companion.modelsetup.network.ServerboundModelSetupApply;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Vanilla-widget Agent configuration hub. The server remains authoritative
 * for identity availability, permissions, persistence, and credentials.
 */
final class ModelSetupScreen extends Screen {
    private static final AtomicLong REQUEST_IDS =
        new AtomicLong(System.nanoTime() & Long.MAX_VALUE);
    private static final int SIDEBAR_WIDTH = 146;
    private static final int MIN_SIDEBAR_WIDTH = 92;
    private static final int MIN_FIELD_WIDTH = 136;
    private static final int CONTENT_GAP = 10;
    private static final int FIELD_HEIGHT = 20;
    private static final int FORM_VIEW_TOP = 30;
    private static final int FORM_FOOTER_HEIGHT = 56;
    private static final int IDENTITY_HEADING_Y = 34;
    private static final int AGENT_NAME_Y = 62;
    private static final int NAME_STATUS_Y = 86;
    private static final int PERSONALIZATION_Y = 100;
    private static final int SKIN_Y = 126;
    private static final int MODEL_HEADING_Y = 164;
    private static final int API_KEY_Y = 192;
    private static final int BASE_URL_Y = 232;
    private static final int MODEL_NAME_Y = 272;
    private static final int SYSTEM_PROMPT_Y = 312;
    private static final int SYSTEM_PROMPT_HEIGHT = 64;
    private static final int FORM_CONTENT_BOTTOM =
        SYSTEM_PROMPT_Y + SYSTEM_PROMPT_HEIGHT;

    private final Screen parent;
    private SecretEditBox apiKey;
    private EditBox baseUrl;
    private EditBox modelName;
    private EditBox agentName;
    private CycleButton<AgentAccentColor> accentColor;
    private TemperatureSlider temperature;
    private MultiLineEditBox systemPrompt;
    private Button selectedAgent;
    private Button saveButton;
    private Button skinButton;
    private Button tutorialPrevious;
    private Button tutorialNext;
    private Button tutorialSkip;
    private EditBox firstMessage;
    private byte[] sessionToken = new byte[0];
    private long sessionRequestId;
    private boolean canEdit;
    private boolean credentialAvailable;
    private boolean gatewayReady;
    private boolean pending;
    private boolean restartRequired;
    private boolean bodyActive;
    private boolean onboardingCompleted;
    private boolean onboardingStarted;
    private int tutorialStep = -1;
    private int scrollOffset;
    private int probeRefreshTicks;
    private boolean pollProbeState;
    private String pendingFirstMessage = "";
    private String statusCode = "connecting";

    ModelSetupScreen(final Screen parent) {
        super(Component.translatable("mcai_companion.screen.agents.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final Layout layout = layout();
        selectedAgent = addRenderableWidget(
            Button.builder(
                Component.literal("MCAI"),
                ignored -> statusCode = "agent_selected"
            )
                .bounds(
                    layout.sidebarX(),
                    50,
                    Math.max(36, layout.sidebarWidth() - 16),
                    20
                )
                .build()
        );
        selectedAgent.active = false;

        agentName = addRenderableWidget(new EditBox(
            font,
            layout.fieldX(),
            contentY(AGENT_NAME_Y),
            layout.fieldWidth(),
            FIELD_HEIGHT,
            Component.translatable("mcai_companion.field.agent_name")
        ));
        agentName.setMaxLength(
            ModelSetupWireLimits.MAX_AGENT_NAME_CHARACTERS
        );
        agentName.setHint(Component.literal("Agent_1"));

        accentColor = addRenderableWidget(
            CycleButton
                .builder(
                    color -> Component.translatable(
                        "mcai_companion.color." + color.serializedName()
                    ),
                    AgentAccentColor.EMERALD
                )
                .withValues(Arrays.asList(AgentAccentColor.values()))
                .create(
                    layout.fieldX(),
                    contentY(PERSONALIZATION_Y),
                    layout.halfFieldWidth(),
                    FIELD_HEIGHT,
                    Component.translatable(
                        "mcai_companion.field.accent_color"
                    ),
                    (button, value) -> updateSaveButton()
                )
        );
        accentColor.setTooltip(Tooltip.create(Component.translatable(
            "mcai_companion.tooltip.accent_color"
        )));

        temperature = addRenderableWidget(new TemperatureSlider(
            layout.fieldX()
                + layout.halfFieldWidth() + 8,
            contentY(PERSONALIZATION_Y),
            layout.fieldWidth() - layout.halfFieldWidth() - 8,
            0.2
        ));

        skinButton = addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "mcai_companion.button.skin_setup"
                ),
                ignored -> minecraft.gui.setScreen(
                    new SkinSetupScreen(this)
                )
            )
                .bounds(
                    layout.fieldX(),
                    contentY(SKIN_Y),
                    layout.fieldWidth(),
                    20
                )
                .build()
        );

        apiKey = addRenderableWidget(new SecretEditBox(
            font,
            layout.fieldX(),
            contentY(API_KEY_Y),
            layout.fieldWidth(),
            FIELD_HEIGHT,
            Component.literal("API Key")
        ));
        apiKey.setMaxLength(ModelSetupWireLimits.MAX_API_KEY_UTF8_BYTES);
        apiKey.setHint(Component.translatable(
            "mcai_companion.hint.api_key"
        ));

        baseUrl = addRenderableWidget(new EditBox(
            font,
            layout.fieldX(),
            contentY(BASE_URL_Y),
            layout.fieldWidth(),
            FIELD_HEIGHT,
            Component.literal("Base URL")
        ));
        baseUrl.setMaxLength(
            ModelSetupWireLimits.MAX_BASE_URL_CHARACTERS
        );
        baseUrl.setValue(CompanionConfig.MODEL_BASE_URL.get());

        modelName = addRenderableWidget(new EditBox(
            font,
            layout.fieldX(),
            contentY(MODEL_NAME_Y),
            layout.fieldWidth(),
            FIELD_HEIGHT,
            Component.literal("Model Name")
        ));
        modelName.setMaxLength(
            ModelSetupWireLimits.MAX_MODEL_NAME_CHARACTERS
        );
        modelName.setValue(CompanionConfig.MODEL_NAME.get());

        systemPrompt = addRenderableWidget(
            MultiLineEditBox.builder()
                .setX(layout.fieldX())
                .setY(contentY(SYSTEM_PROMPT_Y))
                .setPlaceholder(Component.translatable(
                    "mcai_companion.hint.system_prompt"
                ))
                .build(
                    font,
                    layout.fieldWidth(),
                    SYSTEM_PROMPT_HEIGHT,
                    Component.translatable(
                        "mcai_companion.field.system_prompt"
                    )
                )
        );
        systemPrompt.setCharacterLimit(
            ModelSetupWireLimits.MAX_SYSTEM_PROMPT_CHARACTERS
        );

        final int buttonY = height - 28;
        saveButton = addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "mcai_companion.button.save_verify"
                ),
                ignored -> save()
            )
                .bounds(
                    layout.fieldX(),
                    buttonY,
                    layout.halfFieldWidth(),
                    20
                )
                .build()
        );
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_BACK, ignored -> onClose())
                .bounds(
                    layout.fieldX() + layout.halfFieldWidth() + 8,
                    buttonY,
                    layout.fieldWidth() - layout.halfFieldWidth() - 8,
                    20
                )
                .build()
        );

        tutorialPrevious = addRenderableWidget(
            Button.builder(
                Component.translatable("mcai_companion.tutorial.previous"),
                ignored -> setTutorialStep(tutorialStep - 1)
            ).bounds(
                layout.sidebarX(),
                height - 76,
                layout.tutorialHalfWidth(),
                20
            ).build()
        );
        tutorialNext = addRenderableWidget(
            Button.builder(
                Component.translatable("mcai_companion.tutorial.next"),
                ignored -> advanceTutorial()
            ).bounds(
                layout.sidebarX() + layout.tutorialHalfWidth() + 6,
                height - 76,
                layout.sidebarWidth() - layout.tutorialHalfWidth() - 6,
                20
            ).build()
        );
        tutorialSkip = addRenderableWidget(
            Button.builder(
                Component.translatable("mcai_companion.tutorial.skip"),
                ignored -> finishTutorial("")
            ).bounds(
                layout.sidebarX(),
                height - 50,
                layout.sidebarWidth(),
                20
            ).build()
        );
        firstMessage = addRenderableWidget(new EditBox(
            font,
            layout.sidebarX(),
            height - 104,
            layout.sidebarWidth(),
            FIELD_HEIGHT,
            Component.translatable(
                "mcai_companion.tutorial.first_message"
            )
        ));
        firstMessage.setMaxLength(512);
        firstMessage.setHint(Component.literal(
            "Follow me and gather wood"
        ));

        agentName.setResponder(ignored -> updateSaveButton());
        baseUrl.setResponder(ignored -> updateSaveButton());
        modelName.setResponder(ignored -> updateSaveButton());
        apiKey.setResponder(ignored -> updateSaveButton());
        systemPrompt.setValueListener(ignored -> updateSaveButton());
        applyFormScroll();

        final var connection = minecraft.getConnection();
        if (connection == null) {
            canEdit = false;
            statusCode = "join_world_to_configure";
            updateSaveButton();
            updateTutorialWidgets();
            return;
        }
        requestState();
        setInitialFocus(agentName);
        updateTutorialWidgets();
    }

    private void requestState() {
        clearSession();
        pending = true;
        statusCode = "requesting_server_state";
        sessionRequestId = nextRequestId();
        updateSaveButton();
        try {
            ModelSetupNetwork.requestState(sessionRequestId);
        } catch (RuntimeException exception) {
            pending = false;
            statusCode = "server_channel_unavailable";
            updateSaveButton();
        }
    }

    void acceptState(final ClientboundModelSetupState state) {
        if (state.requestId() != sessionRequestId) {
            return;
        }
        clearSession();
        sessionToken = state.sessionToken();
        canEdit = state.canEdit();
        credentialAvailable = state.credentialAvailable();
        gatewayReady = state.gatewayReady();
        pending = state.probeInFlight();
        pollProbeState = state.probeInFlight();
        probeRefreshTicks = pollProbeState ? 20 : 0;
        restartRequired = state.restartRequired();
        bodyActive = state.bodyActive();
        onboardingCompleted = state.onboardingCompleted();
        statusCode = state.statusCode();
        if (!state.baseUrl().isEmpty()) {
            baseUrl.setValue(state.baseUrl());
        }
        if (!state.modelName().isEmpty()) {
            modelName.setValue(state.modelName());
        }
        if (!state.agentName().isEmpty()) {
            agentName.setValue(state.agentName());
            selectedAgent.setMessage(Component.literal(state.agentName()));
        }
        accentColor.setValue(AgentAccentColor.parse(state.accentColor()));
        temperature.setTemperature(state.temperature());
        systemPrompt.setValue(state.systemPrompt());
        apiKey.setValue("");

        if (!onboardingCompleted && !onboardingStarted) {
            onboardingStarted = true;
            setTutorialStep(0);
        }
        if (onboardingCompleted
            && statusCode.startsWith("saved_verified")
            && !pendingFirstMessage.isBlank()
            && minecraft.getConnection() != null) {
            minecraft.getConnection().sendChat(
                pendingFirstMessage.strip()
            );
            pendingFirstMessage = "";
            statusCode = "first_interaction_sent";
        }
        updateSaveButton();
        updateTutorialWidgets();
    }

    private void save() {
        if (!canEdit
            || pending
            || sessionToken.length == 0
            || minecraft.getConnection() == null) {
            return;
        }
        final AgentNameRules.Validation name =
            AgentNameRules.validateSyntax(agentName.getValue());
        if (!name.accepted()) {
            statusCode = name.code();
            updateSaveButton();
            return;
        }
        try {
            new EndpointValidator().validate(
                baseUrl.getValue(),
                modelName.getValue()
            );
        } catch (EndpointValidationException exception) {
            statusCode = exception.code();
            updateSaveButton();
            return;
        }
        if (!credentialAvailable && apiKey.getValue().isEmpty()) {
            statusCode = "missing_api_key";
            updateSaveButton();
            return;
        }

        final byte[] credential = apiKey
            .getValue()
            .getBytes(StandardCharsets.UTF_8);
        apiKey.setValue("");
        if (credential.length
            > ModelSetupWireLimits.MAX_API_KEY_UTF8_BYTES) {
            Arrays.fill(credential, (byte) 0);
            statusCode = "invalid_api_key";
            updateSaveButton();
            return;
        }
        final byte[] requestToken = sessionToken;
        sessionToken = new byte[0];
        pending = true;
        pollProbeState = false;
        statusCode = "saving_and_verifying";
        updateSaveButton();
        try {
            ModelSetupNetwork.apply(new ServerboundModelSetupApply(
                sessionRequestId,
                requestToken,
                credential,
                baseUrl.getValue(),
                modelName.getValue(),
                name.normalized(),
                accentColor.getValue().serializedName(),
                temperature.temperature(),
                systemPrompt.getValue(),
                onboardingCompleted,
                true
            ));
        } catch (RuntimeException exception) {
            pending = false;
            canEdit = false;
            statusCode = "server_channel_unavailable";
        } finally {
            Arrays.fill(requestToken, (byte) 0);
            Arrays.fill(credential, (byte) 0);
            updateSaveButton();
        }
    }

    private void updateSaveButton() {
        if (saveButton == null) {
            return;
        }
        final boolean credentialReady =
            credentialAvailable
                || (apiKey != null && !apiKey.getValue().isEmpty());
        final boolean validName = agentName != null
            && AgentNameRules.validateSyntax(
                agentName.getValue()
            ).accepted();
        saveButton.active = canEdit
            && !pending
            && sessionToken.length > 0
            && minecraft.getConnection() != null;
        if (saveButton.active && (!credentialReady || !validName)) {
            saveButton.setTooltip(Tooltip.create(Component.translatable(
                !credentialReady
                    ? "mcai_companion.status.missing_api_key"
                    : "mcai_companion.status.name_invalid"
            )));
        } else {
            saveButton.setTooltip(null);
        }
        if (skinButton != null) {
            skinButton.active = minecraft.getConnection() != null;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!pollProbeState
                || minecraft.getConnection() == null) {
            return;
        }
        probeRefreshTicks--;
        if (probeRefreshTicks <= 0) {
            probeRefreshTicks = 20;
            requestState();
        }
    }

    private void advanceTutorial() {
        if (tutorialStep < 3) {
            setTutorialStep(tutorialStep + 1);
            return;
        }
        finishTutorial(firstMessage.getValue());
    }

    private void finishTutorial(final String initialMessage) {
        onboardingCompleted = true;
        pendingFirstMessage = initialMessage.strip();
        setTutorialStep(-1);
        statusCode = pendingFirstMessage.isEmpty()
            ? "tutorial_completed_save_required"
            : "tutorial_ready_save_and_verify";
        updateSaveButton();
        if (saveButton.active) {
            save();
        }
    }

    private void setTutorialStep(final int step) {
        tutorialStep = Math.max(-1, Math.min(3, step));
        updateTutorialWidgets();
    }

    private void updateTutorialWidgets() {
        if (tutorialPrevious == null) {
            return;
        }
        final boolean visible = tutorialStep >= 0;
        tutorialPrevious.visible = visible;
        tutorialPrevious.active = visible && tutorialStep > 0;
        tutorialNext.visible = visible;
        tutorialNext.setMessage(Component.translatable(
            tutorialStep == 3
                ? "mcai_companion.tutorial.finish"
                : "mcai_companion.tutorial.next"
        ));
        tutorialSkip.visible = visible;
        firstMessage.visible = visible && tutorialStep == 3;
    }

    @Override
    public void extractRenderState(
        final GuiGraphicsExtractor graphics,
        final int mouseX,
        final int mouseY,
        final float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        final Layout layout = layout();
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        graphics.text(
            font,
            Component.translatable("mcai_companion.sidebar.agents"),
            layout.sidebarX(),
            34,
            0xFFA0A0A0
        );
        formText(
            graphics,
            Component.translatable("mcai_companion.section.identity"),
            IDENTITY_HEADING_Y,
            accentColor == null
                ? 0xFFFFFFFF
                : accentColor.getValue().argb()
        );
        label(
            graphics,
            "mcai_companion.field.agent_name",
            AGENT_NAME_Y
        );
        formText(
            graphics,
            Component.translatable("mcai_companion.section.model"),
            MODEL_HEADING_Y,
            0xFFFFFFFF
        );
        label(graphics, "mcai_companion.field.api_key", API_KEY_Y);
        label(graphics, "mcai_companion.field.base_url", BASE_URL_Y);
        label(graphics, "mcai_companion.field.model_name", MODEL_NAME_Y);
        label(
            graphics,
            "mcai_companion.field.system_prompt",
            SYSTEM_PROMPT_Y
        );

        final AgentNameRules.Validation validation =
            AgentNameRules.validateSyntax(
                agentName == null ? "" : agentName.getValue()
            );
        final String nameCode = validation.accepted()
            ? (bodyActive
                ? "name_available_body_active"
                : "name_available")
            : validation.code();
        formText(
            graphics,
            Component.translatable("mcai_companion.status." + nameCode),
            NAME_STATUS_Y,
            validation.accepted() ? 0xFF55FF55 : 0xFFFF5555
        );
        if (maxScrollOffset() > 0) {
            graphics.text(
                font,
                Component.literal(scrollOffset == 0 ? "↓" : "↕"),
                layout.fieldX() + layout.fieldWidth() - 8,
                FORM_VIEW_TOP,
                0xFFFFFFFF
            );
        }

        final int statusY = height - 42;
        final var statusText = Component.literal("Status: ")
            .append(statusComponent());
        if (gatewayReady) {
            statusText.append(" · verified");
        }
        if (restartRequired) {
            statusText.append(" · restart required");
        }
        graphics.text(
            font,
            statusText,
            layout.fieldX(),
            statusY,
            statusCode.startsWith("saved_verified")
                || statusCode.equals("first_interaction_sent")
                ? 0xFF55FF55
                : 0xFFB0B0B0
        );
        if (tutorialStep >= 0) {
            graphics.text(
                font,
                Component.translatable(
                    "mcai_companion.tutorial.step",
                    tutorialStep + 1,
                    4
                ),
                layout.sidebarX(),
                82,
                0xFFFFAA00
            );
            graphics.textWithWordWrap(
                font,
                Component.translatable(
                    "mcai_companion.tutorial.body." + tutorialStep
                ),
                layout.sidebarX(),
                96,
                layout.sidebarWidth(),
                0xFFFFFFFF
            );
        } else {
            graphics.textWithWordWrap(
                font,
                Component.translatable(
                    "mcai_companion.sidebar.multi_agent_note"
                ),
                layout.sidebarX(),
                86,
                130,
                0xFF909090
            );
        }
    }

    /**
     * Keep provider failures understandable in the vanilla screen. Unknown
     * server codes remain visible verbatim for diagnostics, while the common
     * credential states tell the player exactly why a replacement key is
     * required instead of implying that the Agent is merely thinking.
     */
    private Component statusComponent() {
        final String key = switch (statusCode) {
            case "ready" ->
                "mcai_companion.status.ready";
            case "api_key_rejected" ->
                "mcai_companion.status.api_key_rejected";
            case "api_key_rejected_requires_replacement" ->
                "mcai_companion.status.api_key_rejected_requires_replacement";
            case "credential_restore_failed" ->
                "mcai_companion.status.credential_restore_failed";
            case "saved_verified_keychain" ->
                "mcai_companion.status.saved_verified_keychain";
            case "saved_verified_windows_dpapi" ->
                "mcai_companion.status.saved_verified_windows_dpapi";
            case "saved_verified_linux_secret_service" ->
                "mcai_companion.status.saved_verified_linux_secret_service";
            case "saved_verified_secure_store" ->
                "mcai_companion.status.saved_verified_secure_store";
            case "saved_verified_unchanged" ->
                "mcai_companion.status.saved_verified_unchanged";
            case "saved_verified_process" ->
                "mcai_companion.status.saved_verified_process";
            case "saved_verified_process_restart_required" ->
                "mcai_companion.status.saved_verified_process_restart_required";
            case "saved_probe_authentication" ->
                "mcai_companion.status.saved_probe_authentication";
            default -> "";
        };
        return key.isEmpty()
            ? Component.literal(statusCode)
            : Component.translatable(key);
    }

    private void label(
        final GuiGraphicsExtractor graphics,
        final String translationKey,
        final int fieldY
    ) {
        formText(
            graphics,
            Component.translatable(translationKey),
            fieldY - 12,
            0xFFA0A0A0
        );
    }

    private void formText(
        final GuiGraphicsExtractor graphics,
        final Component text,
        final int contentY,
        final int color
    ) {
        final int y = contentY(contentY);
        if (y < FORM_VIEW_TOP || y + font.lineHeight > formViewBottom()) {
            return;
        }
        graphics.text(font, text, layout().fieldX(), y, color);
    }

    @Override
    public boolean mouseScrolled(
        final double mouseX,
        final double mouseY,
        final double scrollX,
        final double scrollY
    ) {
        final Layout layout = layout();
        if (mouseX >= layout.sidebarX()
            && mouseX < layout.fieldX() + layout.fieldWidth()
            && mouseY >= FORM_VIEW_TOP
            && mouseY < formViewBottom()
            && maxScrollOffset() > 0) {
            final int direction = scrollY > 0.0 ? -1 : scrollY < 0.0 ? 1 : 0;
            if (direction != 0) {
                scrollOffset = Math.max(
                    0,
                    Math.min(maxScrollOffset(), scrollOffset + direction * 24)
                );
                applyFormScroll();
                return true;
            }
        }
        return super.mouseScrolled(
            mouseX,
            mouseY,
            scrollX,
            scrollY
        );
    }

    private void applyFormScroll() {
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset));
        positionFormWidget(agentName, AGENT_NAME_Y);
        positionFormWidget(accentColor, PERSONALIZATION_Y);
        positionFormWidget(temperature, PERSONALIZATION_Y);
        positionFormWidget(skinButton, SKIN_Y);
        positionFormWidget(apiKey, API_KEY_Y);
        positionFormWidget(baseUrl, BASE_URL_Y);
        positionFormWidget(modelName, MODEL_NAME_Y);
        positionFormWidget(systemPrompt, SYSTEM_PROMPT_Y);
    }

    private void positionFormWidget(
        final AbstractWidget widget,
        final int unscrolledY
    ) {
        if (widget == null) {
            return;
        }
        widget.setY(contentY(unscrolledY));
        widget.visible = widget.getY() >= FORM_VIEW_TOP
            && widget.getBottom() <= formViewBottom();
    }

    private int contentY(final int unscrolledY) {
        return unscrolledY - scrollOffset;
    }

    private int formViewBottom() {
        return Math.max(FORM_VIEW_TOP, height - FORM_FOOTER_HEIGHT);
    }

    private int maxScrollOffset() {
        return Math.max(0, FORM_CONTENT_BOTTOM - formViewBottom());
    }

    @Override
    public void onClose() {
        if (apiKey != null) {
            apiKey.setValue("");
        }
        clearSession();
        minecraft.gui.setScreen(parent);
    }

    private Layout layout() {
        final int contentWidth = Math.min(680, Math.max(260, width - 24));
        final int contentX = (width - contentWidth) / 2;
        final int sidebarX = contentX;
        final int sidebarWidth = Math.min(
            SIDEBAR_WIDTH,
            Math.max(MIN_SIDEBAR_WIDTH, contentWidth / 3)
        );
        final int fieldX = contentX + sidebarWidth + CONTENT_GAP;
        final int fieldWidth = Math.max(
            MIN_FIELD_WIDTH,
            contentWidth - sidebarWidth - CONTENT_GAP
        );
        return new Layout(sidebarX, sidebarWidth, fieldX, fieldWidth);
    }

    private void clearSession() {
        Arrays.fill(sessionToken, (byte) 0);
        sessionToken = new byte[0];
    }

    private static long nextRequestId() {
        return REQUEST_IDS.updateAndGet(previous ->
            previous == Long.MAX_VALUE ? 1 : previous + 1
        );
    }

    private record Layout(
        int sidebarX,
        int sidebarWidth,
        int fieldX,
        int fieldWidth
    ) {
        int halfFieldWidth() {
            return Math.max(60, (fieldWidth - 8) / 2);
        }

        int tutorialHalfWidth() {
            return Math.max(36, (sidebarWidth - 6) / 2);
        }
    }

    private static final class TemperatureSlider
        extends AbstractSliderButton {
        private TemperatureSlider(
            final int x,
            final int y,
            final int width,
            final double temperature
        ) {
            super(
                x,
                y,
                width,
                FIELD_HEIGHT,
                Component.empty(),
                Math.max(0.0, Math.min(1.0, temperature))
            );
            updateMessage();
        }

        double temperature() {
            return Math.round(value * 10.0) / 10.0;
        }

        void setTemperature(final double temperature) {
            value = Math.max(0.0, Math.min(1.0, temperature));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(
                "mcai_companion.field.temperature",
                String.format(java.util.Locale.ROOT, "%.1f", temperature())
            ));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }
    }
}
