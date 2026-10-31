package com.google.geminiclone;

import java.util.List;

public class ChatMessage {
    private String text;
    private final boolean isUser;
    private boolean isGenerating;
    private String thinkingLevel;
    private String modelName;
    private final long timestamp;

    // Optional File/Image Attachment
    private String attachmentUri;
    private String attachmentMimeType;
    private String attachmentName;

    // Cached parsed representations (zero regex/span allocation on RecyclerView scroll)
    private transient CharSequence cachedMarkdownText;
    private transient List<MarkdownParser.MessageBlock> cachedBlocks;

    public ChatMessage(String text, boolean isUser) {
        this.text = text;
        this.isUser = isUser;
        this.isGenerating = false;
        this.timestamp = System.currentTimeMillis();
    }

    public ChatMessage(String text, boolean isUser, boolean isGenerating, String thinkingLevel, String modelName) {
        this.text = text;
        this.isUser = isUser;
        this.isGenerating = isGenerating;
        this.thinkingLevel = thinkingLevel;
        this.modelName = modelName;
        this.timestamp = System.currentTimeMillis();
    }

    public String getAttachmentUri() {
        return attachmentUri;
    }

    public void setAttachmentUri(String attachmentUri) {
        this.attachmentUri = attachmentUri;
    }

    public String getAttachmentMimeType() {
        return attachmentMimeType;
    }

    public void setAttachmentMimeType(String attachmentMimeType) {
        this.attachmentMimeType = attachmentMimeType;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
        this.attachmentName = attachmentName;
    }

    public boolean hasAttachment() {
        return attachmentUri != null && !attachmentUri.isEmpty();
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        if (this.text == null || !this.text.equals(text)) {
            this.text = text;
            this.cachedMarkdownText = null;
            this.cachedBlocks = null;
        }
    }

    public CharSequence getParsedMarkdownText() {
        if (cachedMarkdownText == null) {
            cachedMarkdownText = MarkdownParser.parseMarkdownText(text);
        }
        return cachedMarkdownText;
    }

    public List<MarkdownParser.MessageBlock> getParsedBlocks() {
        if (cachedBlocks == null) {
            cachedBlocks = MarkdownParser.parseBlocks(text);
        }
        return cachedBlocks;
    }

    public boolean isUser() {
        return isUser;
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void setGenerating(boolean generating) {
        isGenerating = generating;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
