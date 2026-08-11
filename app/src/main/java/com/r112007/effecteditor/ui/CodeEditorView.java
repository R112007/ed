package com.r112007.effecteditor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Layout;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import android.content.ClipData;
import android.content.ClipboardManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.r112007.effecteditor.R;
import com.itsaky.androidide.treesitter.TSLanguage;
import com.itsaky.androidide.treesitter.TSParser;
import com.itsaky.androidide.treesitter.TSTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight code editor backed by Tree-sitter for fast incremental parsing.
 * <p>
 * Tree-sitter acts as the "face": it provides real-time syntax highlighting,
 * brace matching and lightweight structural validation on every keystroke.
 * Deep semantic analysis is intentionally left to
 * {@link com.r112007.effecteditor.analysis.EffectAnalyzer}
 * using JavaParser, triggered only by explicit user actions.
 */
public class CodeEditorView extends EditText {

    private static final int DEBOUNCE_MS = 250;
    // Minimum gutter width in dp; grows automatically when line count increases.
    private static final int MIN_GUTTER_DP = 48;
    private static final int GUTTER_PADDING_DP = 12;

    private final Paint lineNumberPaint = new Paint();
    private final Paint gutterBackgroundPaint = new Paint();
    private final Paint gutterDividerPaint = new Paint();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int gutterWidthPx = 0;
    private int lastLogicalLineCount = 0;
    private final ExecutorService highlightExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ts-highlighter");
        t.setDaemon(true);
        return t;
    });

    private TSParser parser;
    private TSLanguage javaLanguage;
    private boolean highlighting = false;
    private boolean applyingHighlight = false;
    private final Object highlightLock = new Object();
    private AutoCompletePopup autoCompletePopup;
    /** True while a completion item is being inserted; prevents auto-reopening the popup. */
    private boolean applyingCompletion = false;

    private final Runnable highlightRunnable = this::doHighlight;

    // Undo/redo history (MT Manager style).
    private static final int HISTORY_DEBOUNCE_MS = 400;
    private static final int MAX_HISTORY_SIZE = 50;
    private final ArrayDeque<TextState> undoStack = new ArrayDeque<>();
    private final ArrayDeque<TextState> redoStack = new ArrayDeque<>();
    private final Runnable commitHistoryRunnable = this::commitHistory;
    private TextState pendingHistoryState;
    private boolean isUndoOrRedo;
    private boolean historyEnabled;
    private OnHistoryChangeListener historyChangeListener;

    // Pinch-to-zoom text size.
    private ScaleGestureDetector scaleDetector;
    private float currentTextSizePx;
    private float minTextSizePx;
    private float maxTextSizePx;

    public CodeEditorView(Context context) {
        super(context);
        init(context);
    }

    public CodeEditorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CodeEditorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setTypeface(Typeface.MONOSPACE);
        setBackgroundColor(ContextCompat.getColor(context, R.color.editor_bg));
        setTextColor(ContextCompat.getColor(context, R.color.editor_text));
        setTextSize(14f);
        setHorizontallyScrolling(true);
        setInputType(EditorInfo.TYPE_CLASS_TEXT
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        lineNumberPaint.setColor(0xFF565F89);
        lineNumberPaint.setTextSize(getTextSize());
        lineNumberPaint.setTypeface(Typeface.MONOSPACE);
        lineNumberPaint.setAntiAlias(true);
        lineNumberPaint.setTextAlign(Paint.Align.RIGHT);

        // Slightly darker than editor_bg so the gutter is visible.
        gutterBackgroundPaint.setColor(0xFF13141C);

        // Subtle vertical line separating gutter from code.
        gutterDividerPaint.setColor(0xFF3B4261);
        gutterDividerPaint.setStrokeWidth(1f * getResources().getDisplayMetrics().density);

        currentTextSizePx = getTextSize();
        float density = getResources().getDisplayMetrics().density;
        gutterWidthPx = (int) (MIN_GUTTER_DP * density);
        setPadding(gutterWidthPx, getPaddingTop(), getPaddingRight(), getPaddingBottom());
        minTextSizePx = 10f * density;
        maxTextSizePx = 40f * density;

        // Ensure custom drawing is enabled even if the background is changed later.
        setWillNotDraw(false);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        currentTextSizePx = Math.max(minTextSizePx,
                                Math.min(maxTextSizePx, currentTextSizePx * detector.getScaleFactor()));
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, currentTextSizePx);
                        lineNumberPaint.setTextSize(currentTextSizePx);
                        invalidate();
                        return true;
                    }
                });

        try {
            Class<?> langClass = null;
            for (String name : new String[] {
                    "com.itsaky.androidide.treesitter.java.TreeSitterJava",
                    "com.itsaky.androidide.treesitter.java.TSLanguageJava" }) {
                try {
                    langClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (langClass != null) {
                Object langObj = langClass.getMethod("getInstance").invoke(null);
                if (langObj instanceof TSLanguage) {
                    javaLanguage = (TSLanguage) langObj;
                } else {
                    javaLanguage = (TSLanguage) langObj.getClass().getMethod("getLanguage").invoke(langObj);
                }
                parser = TSParser.create();
                parser.setLanguage(javaLanguage);
            }
        } catch (Throwable t) {
            parser = null;
            javaLanguage = null;
        }

        autoCompletePopup = new AutoCompletePopup(context);
        autoCompletePopup.setOnCompletionSelectedListener((replacement, importStmt, cursorOffset) -> {
            insertCompletion(replacement, importStmt, cursorOffset);
        });

        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (historyEnabled && !applyingHighlight && !isUndoOrRedo) {
                    savePendingHistoryState();
                    uiHandler.removeCallbacks(commitHistoryRunnable);
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Don't trigger or refresh completion while a completion item is being inserted,
                // otherwise the popup would dismiss and immediately reopen.
                if (applyingCompletion) {
                    return;
                }
                if (count == 1 && start + count <= s.length()) {
                    char c = s.charAt(start + count - 1);
                    if (c == '.' || c == '(' || Character.isJavaIdentifierPart(c)) {
                        post(() -> showAutoComplete());
                        return;
                    }
                }
                // While the popup is open, refresh it on normal text insertion so it stays
                // in sync with the current prefix. Deletions are handled by the hardware
                // delete key (see dispatchKeyEvent) and should close the popup instead.
                if (autoCompletePopup != null && autoCompletePopup.isShowing() && count > 0) {
                    post(() -> showAutoComplete());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (historyEnabled && !applyingHighlight && !isUndoOrRedo) {
                    scheduleHistoryCommit();
                }
                if (!applyingHighlight) {
                    scheduleHighlight();
                }
                updateGutterWidth();
            }
        });
    }

    /**
     * Updates the left gutter width based on the number of logical lines so
     * larger line numbers (e.g. 100+) still fit without overlapping the code.
     */
    private void updateGutterWidth() {
        CharSequence text = getText();
        if (text == null) return;
        int logicalLines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') logicalLines++;
        }
        if (logicalLines == lastLogicalLineCount) return;
        lastLogicalLineCount = logicalLines;

        float density = getResources().getDisplayMetrics().density;
        int digits = Math.max(2, String.valueOf(logicalLines).length());
        float charWidth = lineNumberPaint.measureText("0");
        int newWidth = (int) (Math.max(MIN_GUTTER_DP * density,
                digits * charWidth + GUTTER_PADDING_DP * density * 2));
        if (newWidth != gutterWidthPx) {
            gutterWidthPx = newWidth;
            setPadding(gutterWidthPx, getPaddingTop(), getPaddingRight(), getPaddingBottom());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Let EditText draw its background and text first; otherwise the
        // background color fills the padding area and covers the line numbers.
        super.onDraw(canvas);
        drawLineNumbers(canvas);
    }

    private void drawLineNumbers(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) return;

        int lineCount = layout.getLineCount();
        if (lineCount == 0) return;

        int scrollY = getScrollY();
        int scrollX = getScrollX();
        int viewHeight = getHeight();

        int firstLine = layout.getLineForVertical(scrollY);
        int lastLine = layout.getLineForVertical(scrollY + viewHeight);
        if (lastLine >= lineCount) lastLine = lineCount - 1;

        // Draw a subtle background for the gutter. Because the canvas is translated by
        // scrollX/scrollY, drawing at scrollX keeps the gutter pinned to the view's left edge.
        canvas.drawRect(scrollX, scrollY, scrollX + gutterWidthPx, scrollY + viewHeight,
                gutterBackgroundPaint);

        // Draw a thin divider between the gutter and the code area.
        float dividerX = scrollX + gutterWidthPx - gutterDividerPaint.getStrokeWidth() / 2f;
        canvas.drawLine(dividerX, scrollY, dividerX, scrollY + viewHeight, gutterDividerPaint);

        Editable text = getText();
        float rightPadding = GUTTER_PADDING_DP * getResources().getDisplayMetrics().density;
        // Paint.Align.RIGHT pins the number's right edge to x, so single- and
        // multi-digit line numbers stay aligned on their right side.
        float x = scrollX + gutterWidthPx - rightPadding;

        // Match the vertical position where EditText starts drawing the Layout.
        // The editor uses top|start gravity, so the Layout starts at the top padding.
        int verticalOffset = getCompoundPaddingTop();

        for (int i = firstLine; i <= lastLine; i++) {
            int lineStart = layout.getLineStart(i);
            // Only draw a number on the first visual line of a logical line.
            if (lineStart > 0 && lineStart <= text.length() && text.charAt(lineStart - 1) != '\n') {
                continue;
            }

            int logicalLine = 1;
            for (int j = 0; j < lineStart && j < text.length(); j++) {
                if (text.charAt(j) == '\n') logicalLine++;
            }

            float y = verticalOffset + layout.getLineBaseline(i);
            drawLineNumber(canvas, logicalLine, x, y);
        }
    }

    /**
     * Draws a line number digit by digit using a fixed cell width.  Some device
     * monospace fonts still give digits slightly different widths, which makes
     * the tens column wobble when using simple right-alignment.  Rendering each
     * digit in its own cell keeps the ones and tens columns perfectly aligned.
     */
    private void drawLineNumber(Canvas canvas, int logicalLine, float x, float y) {
        String number = String.valueOf(logicalLine);
        float digitWidth = lineNumberPaint.measureText("0");
        // Small gap between digits so tens and ones are visually distinct.
        float digitGap = digitWidth * 0.2f;
        float cellWidth = digitWidth + digitGap;

        // Draw from right to left: ones digit at x, tens at x - cellWidth, etc.
        float cx = x;
        for (int i = number.length() - 1; i >= 0; i--) {
            String digit = number.substring(i, i + 1);
            canvas.drawText(digit, cx, y, lineNumberPaint);
            cx -= cellWidth;
        }
    }

    /**
     * Undo/redo state holder.
     */
    private static class TextState {
        String text;
        int selectionStart;
        int selectionEnd;
    }

    public interface OnHistoryChangeListener {
        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }

    public void setOnHistoryChangeListener(OnHistoryChangeListener listener) {
        historyChangeListener = listener;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void setHistoryEnabled(boolean enabled) {
        historyEnabled = enabled;
        if (!enabled) {
            uiHandler.removeCallbacks(commitHistoryRunnable);
            pendingHistoryState = null;
        }
    }

    public void clearHistory() {
        uiHandler.removeCallbacks(commitHistoryRunnable);
        pendingHistoryState = null;
        undoStack.clear();
        redoStack.clear();
        notifyHistoryChanged();
    }

    public void undo() {
        if (!canUndo()) return;
        uiHandler.removeCallbacks(commitHistoryRunnable);
        commitHistory();
        TextState current = captureState();
        TextState prev = undoStack.pop();
        redoStack.push(current);
        restoreState(prev);
    }

    public void redo() {
        if (!canRedo()) return;
        uiHandler.removeCallbacks(commitHistoryRunnable);
        commitHistory();
        TextState current = captureState();
        TextState next = redoStack.pop();
        undoStack.push(current);
        restoreState(next);
    }

    private TextState captureState() {
        TextState state = new TextState();
        state.text = getText().toString();
        state.selectionStart = getSelectionStart();
        state.selectionEnd = getSelectionEnd();
        return state;
    }

    private void restoreState(TextState state) {
        isUndoOrRedo = true;
        setText(state.text);
        int len = state.text.length();
        int start = Math.max(0, Math.min(state.selectionStart, len));
        int end = Math.max(start, Math.min(state.selectionEnd, len));
        setSelection(start, end);
        isUndoOrRedo = false;
        notifyHistoryChanged();
    }

    private void savePendingHistoryState() {
        if (applyingHighlight || isUndoOrRedo) return;
        pendingHistoryState = captureState();
    }

    private void scheduleHistoryCommit() {
        if (applyingHighlight || isUndoOrRedo) return;
        uiHandler.removeCallbacks(commitHistoryRunnable);
        uiHandler.postDelayed(commitHistoryRunnable, HISTORY_DEBOUNCE_MS);
    }

    private void commitHistory() {
        if (pendingHistoryState == null) return;
        if (undoStack.size() >= MAX_HISTORY_SIZE) {
            undoStack.removeLast();
        }
        undoStack.push(pendingHistoryState);
        pendingHistoryState = null;
        redoStack.clear();
        notifyHistoryChanged();
    }

    private void notifyHistoryChanged() {
        if (historyChangeListener != null) {
            historyChangeListener.onHistoryChanged(canUndo(), canRedo());
        }
    }

    /**
     * Inserts literal text at the current cursor position.
     */
    public void insertText(CharSequence text) {
        int sel = getSelectionStart();
        if (sel < 0) return;
        Editable editable = getText();
        editable.insert(sel, text);
    }

    public void moveCursorToLineStart() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel < 0) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(sel);
        setSelection(layout.getLineStart(line));
    }

    public void moveCursorToLineEnd() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel < 0) return;
        // Move to the end of the logical line (before the next '\n'), not just
        // the end of the current visual/wrapped line.
        Editable text = getText();
        int len = text.length();
        int end = sel;
        while (end < len && text.charAt(end) != '\n') {
            end++;
        }
        setSelection(end);
    }

    public void moveCursorLeft() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel > 0) setSelection(sel - 1);
    }

    public void moveCursorRight() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel >= 0 && sel < getText().length()) setSelection(sel + 1);
    }

    public void moveCursorUp() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel < 0) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(sel);
        if (line <= 0) {
            setSelection(0);
            return;
        }
        float x = layout.getPrimaryHorizontal(sel);
        setSelection(layout.getOffsetForHorizontal(line - 1, x));
    }

    public void moveCursorDown() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel < 0) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(sel);
        int lastLine = layout.getLineCount() - 1;
        if (line >= lastLine) {
            setSelection(getText().length());
            return;
        }
        float x = layout.getPrimaryHorizontal(sel);
        setSelection(layout.getOffsetForHorizontal(line + 1, x));
    }

    public void pageUp() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel < 0) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(sel);
        int visibleLines = Math.max(1, getHeight() / Math.max(1, getLineHeight()));
        int targetLine = Math.max(0, line - visibleLines);
        float x = layout.getPrimaryHorizontal(sel);
        setSelection(layout.getOffsetForHorizontal(targetLine, x));
    }

    public void pageDown() {
        requestFocus();
        int sel = getSelectionStart();
        if (sel < 0) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(sel);
        int lastLine = layout.getLineCount() - 1;
        int visibleLines = Math.max(1, getHeight() / Math.max(1, getLineHeight()));
        int targetLine = Math.min(lastLine, line + visibleLines);
        float x = layout.getPrimaryHorizontal(sel);
        setSelection(layout.getOffsetForHorizontal(targetLine, x));
    }

    public void copyCurrentLine() {
        requestFocus();
        Editable editable = getText();
        int start = getCurrentLineStart();
        int end = getCurrentLineEndIncludingNewline();
        if (start < 0 || end > editable.length() || start >= end) return;
        String line = editable.subSequence(start, end).toString();
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("code", line));
        }
    }

    public void cutCurrentLine() {
        requestFocus();
        copyCurrentLine();
        deleteCurrentLine(false);
    }

    public void deleteCurrentLine() {
        deleteCurrentLine(true);
    }

    private void deleteCurrentLine(boolean clearClipboard) {
        Editable editable = getText();
        int start = getCurrentLineStart();
        int end = getCurrentLineEndIncludingNewline();
        if (start < 0 || end > editable.length() || start >= end) return;
        editable.delete(start, end);
        int newSel = Math.min(start, editable.length());
        setSelection(newSel);
    }

    public void clearCurrentLine() {
        requestFocus();
        Editable editable = getText();
        int start = getCurrentLineStart();
        int end = getCurrentLineEnd();
        // Keep the trailing newline if there is one.
        if (end > start && end <= editable.length() && editable.charAt(end - 1) == '\n') {
            end--;
        }
        if (start < 0 || end > editable.length() || start > end) return;
        editable.replace(start, end, "");
        setSelection(start);
    }

   /**
 * Inserts the latest clipboard content on a new empty line below the cursor.
 * <p>
 * The steps are:
 * <ol>
 * <li>Move the cursor to the end of the current line.</li>
 * <li>Press Enter (insert '\n') so a new empty line appears below.</li>
 * <li>Read the latest plain-text entry from the system clipboard.</li>
 * <li>Insert that text into the new empty line. Any trailing CR/LF on the
 * clipboard payload is stripped so the pasted content stays on a single
 * line and the cursor lands at the end of the pasted text.</li>
 * </ol>
 */
public void pasteCurrentLine() {
    requestFocus();
    Editable editable = getText();
    if (editable == null) return;
    int sel = getSelectionStart();
    if (sel < 0) sel = 0;
    Layout layout = getLayout();
    if (layout == null) return;

    // === 核心修复：强制在当前行末尾插入换行符 ===
    int lineEnd = layout.getLineEnd(layout.getLineForOffset(sel));
    // 如果当前行末尾已有换行符（非最后一行），则光标实际在下一行开头
    boolean isAtLineEnd = (sel == lineEnd - (getText().charAt(lineEnd - 1) == '\n' ? 1 : 0));
    
    int insertPos;
    if (isAtLineEnd) {
        // 情况1：光标已在行尾 → 插入新换行符创建空行
        editable.insert(sel, "\n");
        insertPos = sel + 1; // 新行开头位置
    } else {
        // 情况2：光标不在行尾 → 先跳到行尾再插入换行符
        int lineStart = layout.getLineStart(layout.getLineForOffset(sel));
        int newlineAt = getText().toString().indexOf('\n', lineStart);
        insertPos = (newlineAt < 0) ? getText().length() : newlineAt;
        editable.insert(insertPos, "\n");
        insertPos++; // 新行开头位置
    }
    // ======================================

    // 获取剪贴板内容（保持原有逻辑）
    ClipboardManager clipboard = (ClipboardManager) getContext()
            .getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null || !clipboard.hasPrimaryClip()) {
        setSelection(insertPos);
        return;
    }

    CharSequence clipText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(getContext());
    if (clipText == null) {
        setSelection(insertPos);
        return;
    }

    // 清理剪贴板文本的尾部换行（保持原有逻辑）
    String text = clipText.toString().replaceAll("[\\r\\n]+$", "");
    if (text.isEmpty()) {
        setSelection(insertPos);
        return;
    }

    editable.insert(insertPos, text);
    setSelection(insertPos + text.length());
}


    private int getCurrentLineStart() {
        int sel = getSelectionStart();
        if (sel < 0) return 0;
        Layout layout = getLayout();
        if (layout == null) {
            String text = getText().toString();
            int start = text.lastIndexOf('\n', sel - 1);
            return start < 0 ? 0 : start + 1;
        }
        int line = layout.getLineForOffset(sel);
        return layout.getLineStart(line);
    }

    private int getCurrentLineEnd() {
        int sel = getSelectionStart();
        if (sel < 0) return getText().length();
        Layout layout = getLayout();
        if (layout == null) return getText().length();
        int line = layout.getLineForOffset(sel);
        return layout.getLineEnd(line);
    }

    private int getCurrentLineEndIncludingNewline() {
        int end = getCurrentLineEnd();
        Editable editable = getText();
        if (end < editable.length() && editable.charAt(end - 1) != '\n') {
            // getLineEnd returned the end of text without a newline on the last line.
            return end;
        }
        return end;
    }

    /**
     * Manually triggers the completion popup at the current cursor position.
     * Called by the "补全" button and by the Tab key.
     */
    public void showAutoComplete() {
        int sel = getSelectionStart();
        if (sel < 0) return;
        CharSequence text = getText();
        int lineStart = Math.max(0, text.toString().lastIndexOf('\n', sel - 1) + 1);
        CharSequence prefix = text.subSequence(lineStart, sel);

        int cursorX = 0;
        int cursorY = 0;
        int lineHeight = getLineHeight();
        android.text.Layout layout = getLayout();
        if (layout != null) {
            int line = layout.getLineForOffset(sel);
            cursorX = (int) layout.getPrimaryHorizontal(sel);
            cursorY = layout.getLineTop(line);
            lineHeight = layout.getLineBottom(line) - layout.getLineTop(line);
        }

        autoCompletePopup.show(this, prefix, text, sel, cursorX, cursorY, lineHeight);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            // Pressing the hardware delete (backspace) key while the completion popup
            // is open simply closes the popup without deleting any editor text.
            if (autoCompletePopup != null && autoCompletePopup.isShowing()
                    && keyCode == KeyEvent.KEYCODE_DEL) {
                autoCompletePopup.dismiss();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_TAB) {
                if (autoCompletePopup != null && autoCompletePopup.isShowing()) {
                    // Tab with an open popup inserts the currently selected completion.
                    autoCompletePopup.commitSelected();
                    return true;
                }
                // Otherwise Tab opens the completion popup.
                showAutoComplete();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void insertCompletion(String completion, String importStmt, int cursorOffset) {
        applyingCompletion = true;
        if (autoCompletePopup != null) {
            autoCompletePopup.dismiss();
        }

        int sel = getSelectionStart();
        if (sel < 0) {
            applyingCompletion = false;
            return;
        }
        Editable editable = getText();

        int start = sel;
        while (start > 0) {
            char c = editable.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '"') {
                start--;
            } else {
                break;
            }
        }

        editable.replace(start, sel, completion);
        int relativeCursor = start + completion.length() + cursorOffset;
        if (relativeCursor < 0) {
            relativeCursor = 0;
        } else if (relativeCursor > editable.length()) {
            relativeCursor = editable.length();
        }
        setSelection(relativeCursor);

        // Auto-append a semicolon for method completions when the call appears to
        // be at the end of a statement. The semicolon goes after the inserted method
        // call, not at the cursor (the cursor may be inside the parentheses).
        int completionEnd = start + completion.length();
        if (isMethodCallCompletion(completion) && shouldAppendSemicolon(editable, completionEnd)) {
            editable.insert(completionEnd, ";");
        }

        if (importStmt != null && !importStmt.isEmpty()) {
            addImportIfMissing(editable, importStmt, relativeCursor);
        }

        applyingCompletion = false;
    }

    private boolean isMethodCallCompletion(String completion) {
        return completion != null && completion.contains("(") && completion.endsWith(")");
    }

    private boolean shouldAppendSemicolon(Editable editable, int cursorPos) {
        int len = editable.length();
        int pos = cursorPos;
        // Skip whitespace after the inserted method call.
        while (pos < len && Character.isWhitespace(editable.charAt(pos))) {
            pos++;
        }
        if (pos >= len) {
            return true;
        }
        char c = editable.charAt(pos);
        // Already has a semicolon or looks like it continues an expression/statement.
        if (c == ';' || c == ')' || c == ']' || c == '}' || c == ',' || c == '.') {
            return false;
        }
        // Operators suggest the method call is part of a larger expression.
        if ("+-*/%<>=!&|^?:".indexOf(c) >= 0) {
            return false;
        }
        // For anything else (newline, comment start, etc.) treat it as end-of-statement.
        return true;
    }

    private void addImportIfMissing(Editable editable, String importStmt, int originalCursor) {
        String text = editable.toString();
        if (text.contains(importStmt)) {
            return;
        }

        int insertPos = 0;
        int lastImport = text.lastIndexOf("import ");
        if (lastImport >= 0) {
            int endOfLine = text.indexOf('\n', lastImport);
            if (endOfLine >= 0) {
                insertPos = endOfLine + 1;
            } else {
                insertPos = text.length();
            }
        }

        boolean needsLeadingBlank = insertPos > 0 && editable.charAt(insertPos - 1) != '\n';
        String insertion = (needsLeadingBlank ? "\n" : "") + importStmt + "\n";
        editable.insert(insertPos, insertion);

        // Inserting text before the cursor shifts the cursor index; restore it relative
        // to the code the user was editing.
        if (insertPos <= originalCursor) {
            setSelection(originalCursor + insertion.length());
        }
    }

    private void scheduleHighlight() {
        uiHandler.removeCallbacks(highlightRunnable);
        uiHandler.postDelayed(highlightRunnable, DEBOUNCE_MS);
    }

    private void doHighlight() {
        if (highlighting) return;
        synchronized (highlightLock) {
            highlighting = true;
        }
        final String text = getText().toString();
        highlightExecutor.execute(() -> {
            Spannable spannable;
            if (parser != null) {
                spannable = parseAndHighlight(text);
            } else {
                spannable = highlightFallback(text);
            }
            uiHandler.post(() -> applyHighlight(spannable));
        });
    }

    private void applyHighlight(Spannable spannable) {
        applyingHighlight = true;
        int sel = getSelectionStart();
        setText(spannable);
        try {
            setSelection(Math.min(sel, spannable.length()));
        } catch (Throwable ignored) {
        }
        applyingHighlight = false;
        synchronized (highlightLock) {
            highlighting = false;
        }
    }

    private Spannable parseAndHighlight(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        try (TSTree tree = parser.parseString(text)) {
            if (tree != null) {
                applyRegexHighlight(builder, text);
                return builder;
            }
        } catch (Throwable t) {
            // Fall back to plain highlighting.
        }
        return highlightFallback(text);
    }

    private Spannable highlightFallback(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        applyRegexHighlight(builder, text);
        return builder;
    }

    /**
     * Regex-based highlighter tuned to look like a typical nvim dark theme
     * (Tokyonight Night inspired). The order matters: earlier matchers win because
     * we only clear existing ForegroundColorSpans before applying.
     */
    private void applyRegexHighlight(SpannableStringBuilder builder, String text) {
    clearSpans(builder);

    // 1. 字符串字面量
    colorMatches(builder, text, "\"([^\"\\\\]|\\\\.)*\"",
            ContextCompat.getColor(getContext(), R.color.editor_string));
    colorMatches(builder, text, "'([^'\\\\]|\\\\.)*'",
            ContextCompat.getColor(getContext(), R.color.editor_string));

    // 2. Java 关键字
    colorMatches(builder, text,
            "\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while)\\b",
            ContextCompat.getColor(getContext(), R.color.editor_keyword));

    // 3. 内置类型
    colorMatches(builder, text,
            "\\b(Effect|EffectContainer|Pal|Color|Draw|Lines|Fill|Mathf|Angles|Interp|Tmp|Fx|TextureRegion|SpriteBatch|Vec2|Vec3|Texture|Pixmap|Core)\\b",
            ContextCompat.getColor(getContext(), R.color.editor_type));

    // 4. 函数名
    colorMatches(builder, text,
            "\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*(?=\\(\\s*)",
            ContextCompat.getColor(getContext(), R.color.editor_function));

    // 5. 数字
    colorMatches(builder, text, "\\b\\d+(\\.\\d+)?f?\\b",
            ContextCompat.getColor(getContext(), R.color.editor_number));
    colorMatches(builder, text, "\\b0x[0-9a-fA-F]+\\b",
            ContextCompat.getColor(getContext(), R.color.editor_number));

    // 6. 运算符
    colorMatches(builder, text,
            "(\\+\\+|--|\\+|-\\b|\\*|/|%|==|!=|<=|>=|<|>|&&|\\|\\||!|&|\\||^|~|<<|>>|>>>|=|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|<<=|>>=|>>>=)",
            ContextCompat.getColor(getContext(), R.color.editor_operator));

    // 7. 内置变量
    colorMatches(builder, text,
            "\\b(e|x|y|rotation|time|lifetime|color|id|fin|fout)\\b",
            ContextCompat.getColor(getContext(), R.color.editor_variable));

    // 8. 标点符号
    colorMatches(builder, text,
            "[{}\\[\\]();,.]",
            ContextCompat.getColor(getContext(), R.color.editor_punctuation));

    // 9. 行注释（移到最后，强制覆盖所有其他高亮，保证注释统一灰色）
    colorMatches(builder, text, "//.*",
            ContextCompat.getColor(getContext(), R.color.editor_comment));
     // 10. 块注释
    colorMatches(builder, text, "/\\*[\\s\\S]*?\\*/",
            ContextCompat.getColor(getContext(), R.color.editor_comment));
}


    private void clearSpans(SpannableStringBuilder builder) {
        ForegroundColorSpan[] spans = builder.getSpans(0, builder.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) {
            builder.removeSpan(span);
        }
    }

    private void colorMatches(SpannableStringBuilder builder, String text, String regex, int color) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            builder.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (scaleDetector != null) {
            scaleDetector.onTouchEvent(event);
            // Prevent text selection from fighting with an active pinch-to-zoom gesture.
            if (scaleDetector.isInProgress()) {
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        InputConnection base = super.onCreateInputConnection(outAttrs);
        return base != null ? new PopupAwareInputConnection(base, true) : null;
    }

    /**
     * Intercepts backspace/delete from software keyboards so that when the
     * completion popup is open the first press closes it without deleting text.
     * Hardware keys are handled in {@link #dispatchKeyEvent}.
     */
    private class PopupAwareInputConnection extends InputConnectionWrapper {
        PopupAwareInputConnection(InputConnection target, boolean mutable) {
            super(target, mutable);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            // Most software keyboards send this for backspace.
            if (autoCompletePopup != null && autoCompletePopup.isShowing()) {
                autoCompletePopup.dismiss();
                return true;
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            // Some keyboards / input methods route hardware backspace here.
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                    && autoCompletePopup != null && autoCompletePopup.isShowing()) {
                autoCompletePopup.dismiss();
                return true;
            }
            return super.sendKeyEvent(event);
        }
    }

    /**
     * Formats the current code by normalizing indentation based on brace nesting.
     * Import statements and blank lines are preserved.
     */
    public void formatCode() {
        Editable editable = getText();
        String text = editable.toString();
        if (text.trim().isEmpty()) return;

        String[] lines = text.split("\n", -1);
        StringBuilder imports = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inBody && trimmed.startsWith("import ")) {
                imports.append(trimmed).append("\n");
            } else {
                inBody = true;
                body.append(line).append("\n");
            }
        }

        String formattedBody = formatJavaBody(body.toString().trim());
        String result = imports.toString();
        if (imports.length() > 0) result += "\n";
        result += formattedBody;

        int oldSel = getSelectionStart();
        int oldLen = editable.length();
        isUndoOrRedo = true;
        editable.replace(0, oldLen, result);
        isUndoOrRedo = false;

        int newLen = result.length();
        int newSel = Math.min(oldSel, newLen);
        if (newSel >= 0) setSelection(newSel);
        notifyHistoryChanged();
    }

    /**
     * Simple brace-aware formatter. Ignores braces inside strings and line comments.
     */
    private String formatJavaBody(String text) {
        String[] rawLines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int indentLevel = 0;
        final String indent = "    ";

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                out.append("\n");
                continue;
            }

            int braceDelta = countBraceDelta(line);
            // A closing brace reduces the indent of its own line.
            int lineIndent = indentLevel + (line.startsWith("}") ? -1 : 0);
            if (lineIndent < 0) lineIndent = 0;

            for (int i = 0; i < lineIndent; i++) {
                out.append(indent);
            }
            out.append(line).append("\n");

            indentLevel += braceDelta;
            if (indentLevel < 0) indentLevel = 0;
        }

        // Trim trailing blank lines but keep a single newline.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private int countBraceDelta(String line) {
        int delta = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean inLineComment = false;
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (inLineComment) {
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < len) {
                    i++;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && i + 1 < len && line.charAt(i + 1) == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }
            if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        highlightExecutor.shutdownNow();
        uiHandler.removeCallbacks(highlightRunnable);
        if (autoCompletePopup != null)
            autoCompletePopup.dismiss();
        if (parser != null)
            parser.close();
        if (javaLanguage != null)
            javaLanguage.close();
    }
}
