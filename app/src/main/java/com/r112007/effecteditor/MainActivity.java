package com.r112007.effecteditor;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.r112007.effecteditor.compiler.EffectCompiler;
import com.r112007.effecteditor.ui.CodeEditorView;
import com.r112007.effecteditor.ui.EffectPreviewView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import arc.Application;
import arc.Application.ApplicationType;
import arc.ApplicationListener;
import arc.Core;
import arc.backend.android.AndroidApplication;
import arc.backend.android.AndroidApplicationConfiguration;
import arc.util.Time;
import mindustry.entities.Effect;

/**
 * Main activity hosting the Effect editor.
 * Extends Arc's AndroidApplication so the OpenGL surface can be embedded with
 * full lifecycle support.
 * Layouts are provided in res/layout (portrait) and res/layout-land (landscape)
 * so the
 * preview pane and code pane swap automatically on orientation changes.
 */
public class MainActivity extends AndroidApplication {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_STORAGE = 1001;
    private static final String DEFAULT_CODE = "import mindustry.entities.Effect;\n" +
            "import mindustry.graphics.Pal;\n" +
            "import arc.graphics.g2d.Draw;\n" +
            "import arc.graphics.g2d.Lines;\n" +
            "import arc.graphics.g2d.Fill;\n" +
            "\n" +
            "new Effect(90f, 260f, e -> {\n" +
            "    Draw.color(Pal.lancerLaser.cpy().mul(1f, 1f, 1f, e.fout()));\n" +
            "    Lines.stroke(e.fin() * 6f);\n" +
            "    Lines.circle(e.x, e.y, e.fin() * 84f);\n" +
            "    Fill.circle(e.x, e.y, e.fout() * 24f);\n" +
            "});";

    private static final String DEFAULT_EXTRA_KEYS = "[ ['ESC','<','>','BACKSLASH','=','^','$','()','{}','[]','ENTER'], "
            +
            "['!','?','@','#',',','(',')','[',']','{','}'], " +
            "['TAB','&',';','/','~','%','*','HOME','UP','END','PGUP'], " +
            "['CTRL','FN','ALT','|','-','+','QUOTE','LEFT','DOWN','RIGHT','PGDN'] ]";

    private static final String PREFS_NAME = "EffectEditorPrefs";
    private static final String KEY_LAST_CODE = "last_code";

    private CodeEditorView codeEditor;
    private EffectPreviewView previewView;
    private TextView statusView;
    private LinearLayout findReplacePanel;
    private static final int MATCH_COLOR_OTHER = 0x662E7D32; // 其他匹配：半透明深绿
    private static final int MATCH_COLOR_CURRENT = 0xFF4CAF50; // 当前选中：亮绿
    private android.widget.EditText frFindInput, frReplaceInput;
    private android.widget.TextView frCount;
    private FrameLayout previewContainer;
    private View rootView;
    private ExecutorService executor;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveCodeRunnable = () -> {
        if (codeEditor != null)
            saveCode(codeEditor.getText().toString());
    };

    // Preserved across configuration changes (the Activity is not recreated because
    // orientation is listed in android:configChanges, but the views are
    // reinflated).
    private String savedCode;
    private Effect compiledEffect;

    private int defaultPreviewWeight = 1;
    private boolean keyboardVisible = false;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long createStart = System.nanoTime();
        StartupLog.log("MainActivity.onCreate started");
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);
            bindViewsAndButtons();
            initPreview();
            setStatus(getString(R.string.status_ready), true);
            StartupLog.logTime("MainActivity.onCreate finished", createStart);
        } catch (Throwable t) {
            StartupLog.log("Fatal error during onCreate: " + t.getMessage());
            Log.e(TAG, "Fatal error during onCreate", t);
            CrashHandler.install(getApplication());
            Toast.makeText(this, "启动失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            throw t;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView != null) {
            previewView.onResume();
            // Sprites may have been copied into the custom sprites folder while the
            // app was in the background; reload the atlas so the next compile sees them.
            previewView.reloadAtlas();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (previewView != null)
            previewView.onPause();
        if (codeEditor != null)
            saveCode(codeEditor.getText().toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeKeyboardListener();
        if (executor != null)
            executor.shutdownNow();
        if (previewView != null)
            previewView.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Because we include orientation in configChanges to keep the GL context,
        // we must rebind the inflated views ourselves.
        removeKeyboardListener();
        rebindViews();
    }

    private void bindViewsAndButtons() {
        long bindStart = System.nanoTime();

        codeEditor = findViewById(R.id.code_editor);
        previewView = findViewById(R.id.preview_view);
        statusView = findViewById(R.id.tv_status);
        previewContainer = findViewById(R.id.preview_container);
        rootView = findViewById(R.id.root);

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "effect-worker");
            t.setDaemon(true);
            return t;
        });

        String persistedCode = loadPersistedCode();
        codeEditor.setText(savedCode != null ? savedCode
                : (persistedCode != null ? persistedCode : DEFAULT_CODE));
        codeEditor.clearHistory();
        codeEditor.setHistoryEnabled(true);

        Button btnCompile = findViewById(R.id.btn_compile);
        Button btnExport = findViewById(R.id.btn_export);
        Button btnReset = findViewById(R.id.btn_reset);
        Button btnComplete = findViewById(R.id.btn_complete);
        Button btnUndo = findViewById(R.id.btn_undo);
        Button btnRedo = findViewById(R.id.btn_redo);
        Button btnToggleKeys = findViewById(R.id.btn_toggle_keys);
        Button btnOrientation = findViewById(R.id.btn_orientation);
        Button btnFormat = findViewById(R.id.btn_format);
        Button btnRefresh = findViewById(R.id.btn_refresh);
        Button btnFindReplace = findViewById(R.id.btn_find_replace);
        if (btnFindReplace != null) {
            btnFindReplace.setOnClickListener(v -> toggleFindReplace());
        }

        Button btnHelp = findViewById(R.id.btn_help);
        if (btnHelp != null) {
            btnHelp.setOnClickListener(v -> showHelp());
        }

        Button btnToggleLineToolsVisibility = findViewById(R.id.btn_toggle_line_tools_visibility);
        View btnToggleLineTools = findViewById(R.id.btn_toggle_line_tools);
        if (btnToggleLineToolsVisibility != null && btnToggleLineTools != null) {
            btnToggleLineToolsVisibility.setOnClickListener(v -> {
                boolean visible = btnToggleLineTools.getVisibility() == View.VISIBLE;
                btnToggleLineTools.setVisibility(visible ? View.GONE : View.VISIBLE);
            });
        }

        btnCompile.setOnClickListener(v -> compileAndRun());
        btnExport.setOnClickListener(v -> exportCode());
        btnReset.setOnClickListener(v -> {
            // Stop rendering first so the GL thread does not touch the old effect
            // while we are clearing the code and compiled state.
            previewView.clearEffect();
            codeEditor.setText(DEFAULT_CODE);
            saveCode(DEFAULT_CODE);
            savedCode = null;
            compiledEffect = null;
            setStatus("已重置为示例代码", true);
        });
        btnComplete.setOnClickListener(v -> {
            if (codeEditor != null) {
                codeEditor.showAutoComplete();
            }
        });
        if (btnUndo != null) {
            btnUndo.setEnabled(codeEditor.canUndo());
            btnUndo.setOnClickListener(v -> codeEditor.undo());
        }
        if (btnRedo != null) {
            btnRedo.setEnabled(codeEditor.canRedo());
            btnRedo.setOnClickListener(v -> codeEditor.redo());
        }
        if (btnToggleKeys != null) {
            btnToggleKeys.setOnClickListener(v -> toggleExtraKeysBar());
        }
        if (btnOrientation != null) {
            boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            btnOrientation.setText(isLandscape ? "竖" : "横");
            btnOrientation.setOnClickListener(v -> toggleOrientation());
        }
        if (btnFormat != null) {
            btnFormat.setOnClickListener(v -> {
                if (codeEditor != null) {
                    codeEditor.formatCode();
                    setStatus("代码已格式化", true);
                }
            });
        }
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                // The user wants the refresh action to behave like killing the
                // app to the background and reopening it: every onCreate path
                // (GL surface, atlas, editor state) is rebuilt from scratch.
                setStatus("正在重启以刷新渲染…", true);
                restartApp();
            });
        }
        codeEditor.setOnHistoryChangeListener((canUndo, canRedo) -> {
            if (btnUndo != null)
                btnUndo.setEnabled(canUndo);
            if (btnRedo != null)
                btnRedo.setEnabled(canRedo);
        });

        codeEditor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                uiHandler.removeCallbacks(saveCodeRunnable);
                uiHandler.postDelayed(saveCodeRunnable, 1000);
            }
        });

        setupKeyboardListener();
        setupStatusView();
        setupExtraKeysBar();
        setupLineTools();
        ensureCustomSpritesFolder();
        StartupLog.logTime("bindViewsAndButtons finished", bindStart);
        initFindReplacePanel();
    }

    private void initFindReplacePanel() {
        FrameLayout codeContainer = (FrameLayout) codeEditor.getParent();
        if (codeContainer == null)
            return;

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (8 * density);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xE61A1B26);
        panel.setPadding(pad, pad, pad, pad);
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        // 查找行
        LinearLayout findRow = new LinearLayout(this);
        findRow.setOrientation(LinearLayout.HORIZONTAL);
        frFindInput = new android.widget.EditText(this);
        frFindInput.setHint("查找");
        frFindInput.setTextColor(getResources().getColor(R.color.editor_text));
        frFindInput.setHintTextColor(0xFF565F89);
        frFindInput.setTextSize(14f);
        frFindInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        frFindInput.setBackground(null);
        frFindInput.setSingleLine(true);
        frFindInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        findRow.addView(frFindInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        frCount = new android.widget.TextView(this);
        frCount.setText("搜索结果: 0");
        frCount.setTextColor(getResources().getColor(R.color.teal_200));
        frCount.setTextSize(12f);
        frCount.setPadding(pad, 0, 0, 0);
        findRow.addView(frCount);
        panel.addView(findRow);

        // 替换行
        frReplaceInput = new android.widget.EditText(this);
        frReplaceInput.setHint("替换为");
        frReplaceInput.setTextColor(getResources().getColor(R.color.editor_text));
        frReplaceInput.setHintTextColor(0xFF565F89);
        frReplaceInput.setTextSize(14f);
        frReplaceInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        frReplaceInput.setBackground(null);
        frReplaceInput.setSingleLine(true);
        panel.addView(frReplaceInput);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = { "上个", "下个", "替换", "全部", "×" };
        android.view.View.OnClickListener[] actions = {
                v -> findPrev(),
                v -> findNext(),
                v -> replaceOne(),
                v -> replaceAll(),
                v -> {
                    findReplacePanel.setVisibility(View.GONE);
                    clearFindHighlights();
                    codeEditor.requestFocus();
                }

        };
        for (int i = 0; i < labels.length; i++) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(labels[i]);
            btn.setTextColor(getResources().getColor(R.color.teal_200));
            btn.setTextSize(12f);
            btn.setOnClickListener(actions[i]);
            btnRow.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        panel.addView(btnRow);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM;

        codeContainer.addView(panel, lp);
        findReplacePanel = panel;

        frFindInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateFindCount();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    private void toggleFindReplace() {
        if (findReplacePanel == null)
            return;
        boolean visible = findReplacePanel.getVisibility() == View.VISIBLE;
        if (visible) {
            findReplacePanel.setVisibility(View.GONE);
            clearFindHighlights();
            codeEditor.requestFocus();
        } else {
            findReplacePanel.setVisibility(View.VISIBLE);
            frFindInput.requestFocus();
            updateFindCount();
        }
    }

    private void clearFindHighlights() {
        android.text.Spannable spannable = codeEditor.getText();
        android.text.style.BackgroundColorSpan[] spans = spannable.getSpans(
                0, spannable.length(), android.text.style.BackgroundColorSpan.class);
        for (android.text.style.BackgroundColorSpan span : spans) {
            spannable.removeSpan(span);
        }
    }

    private void applyFindHighlights(String query, int currentIndex) {
        clearFindHighlights();
        if (query == null || query.isEmpty())
            return;
        String text = codeEditor.getText().toString();
        android.text.Spannable spannable = codeEditor.getText();
        int idx = text.indexOf(query);
        int matchIndex = 0;
        while (idx >= 0) {
            int color = (matchIndex == currentIndex) ? MATCH_COLOR_CURRENT : MATCH_COLOR_OTHER;
            spannable.setSpan(new android.text.style.BackgroundColorSpan(color),
                    idx, idx + query.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            matchIndex++;
            idx = text.indexOf(query, idx + query.length());
        }
    }

    private void findNext() {
        String q = frFindInput.getText().toString();
        if (q.isEmpty())
            return;
        String text = codeEditor.getText().toString();
        // 从当前选中位置的末尾开始搜索，避免重复找到同一个
        int start = codeEditor.getSelectionEnd();
        if (start < 0)
            start = 0;
        int idx = text.indexOf(q, start);
        if (idx < 0)
            idx = text.indexOf(q); // 绕回开头
        if (idx >= 0) {
            codeEditor.setSelection(idx, idx + q.length());
            int currentIndex = 0, temp = text.indexOf(q);
            while (temp >= 0 && temp != idx) {
                currentIndex++;
                temp = text.indexOf(q, temp + q.length());
            }
            applyFindHighlights(q, currentIndex);
        }
        updateFindCount();
    }

    private void findPrev() {
        String q = frFindInput.getText().toString();
        if (q.isEmpty())
            return;
        String text = codeEditor.getText().toString();
        int start = codeEditor.getSelectionStart() - 1;
        if (start < 0)
            start = text.length() - 1;
        int idx = text.lastIndexOf(q, start);
        if (idx < 0)
            idx = text.lastIndexOf(q);
        if (idx >= 0) {
            codeEditor.setSelection(idx, idx + q.length());
            int currentIndex = 0, temp = text.indexOf(q);
            while (temp >= 0 && temp != idx) {
                currentIndex++;
                temp = text.indexOf(q, temp + q.length());
            }
            applyFindHighlights(q, currentIndex);
        }
        updateFindCount();
    }

    private void replaceOne() {
        String q = frFindInput.getText().toString();
        String r = frReplaceInput.getText().toString();
        if (q.isEmpty())
            return;
        android.text.Editable text = codeEditor.getText();
        int selStart = codeEditor.getSelectionStart();
        int selEnd = codeEditor.getSelectionEnd();
        if (selEnd > selStart && text.subSequence(selStart, selEnd).toString().equals(q)) {
            text.replace(selStart, selEnd, r);
            codeEditor.setSelection(selStart + r.length());
        } else {
            findNext();
        }
        codeEditor.dismissAutoComplete();
        updateFindCount();
    }

    private void replaceAll() {
        String q = frFindInput.getText().toString();
        String r = frReplaceInput.getText().toString();
        if (q.isEmpty())
            return;
        android.text.Editable text = codeEditor.getText();
        String str = text.toString();
        int idx = str.indexOf(q);
        while (idx >= 0) {
            text.replace(idx, idx + q.length(), r);
            str = text.toString();
            idx = str.indexOf(q, idx + r.length());
        }
        codeEditor.dismissAutoComplete();
        updateFindCount();
    }

    private void updateFindCount() {
        String q = frFindInput.getText().toString();
        if (q.isEmpty() || frCount == null) {
            if (frCount != null)
                frCount.setText("0/0");
            clearFindHighlights();
            return;
        }
        String text = codeEditor.getText().toString();
        int total = 0, current = 0;
        int selStart = codeEditor.getSelectionStart();
        int idx = text.indexOf(q);
        while (idx >= 0) {
            total++;
            if (idx == selStart)
                current = total;
            idx = text.indexOf(q, idx + q.length());
        }
        frCount.setText(current + "/" + total);
        applyFindHighlights(q, current > 0 ? current - 1 : -1);
    }

    private void showHelp() {
        String help = readAssetText("help.md");
        new AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setMessage(help)
                .setPositiveButton("确定", null)
                .show();
    }

    private String readAssetText(String path) {
        try (InputStream in = getAssets().open(path)) {
            byte[] buf = new byte[in.available()];
            in.read(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "无法加载帮助文档: " + e.getMessage();
        }
    }

    private void setupLineTools() {
        View container = findViewById(R.id.line_tools_container);
        Button toggle = findViewById(R.id.btn_toggle_line_tools);
        if (toggle == null || container == null || codeEditor == null)
            return;

        toggle.setOnClickListener(v -> {
            boolean visible = container.getVisibility() == View.VISIBLE;
            container.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        Button btnCopy = findViewById(R.id.btn_copy_line);
        Button btnCut = findViewById(R.id.btn_cut_line);
        Button btnDelete = findViewById(R.id.btn_delete_line);
        Button btnClear = findViewById(R.id.btn_clear_line);
        Button btnPaste = findViewById(R.id.btn_paste_line);

        // Each row-level action hides the floating toolbar after running, so the
        // menu never stays in the way of the editor once the user has acted on it.
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                codeEditor.copyCurrentLine();
                container.setVisibility(View.GONE);
            });
        }
        if (btnCut != null) {
            btnCut.setOnClickListener(v -> {
                codeEditor.cutCurrentLine();
                container.setVisibility(View.GONE);
            });
        }
        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                codeEditor.deleteCurrentLine();
                container.setVisibility(View.GONE);
            });
        }
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                codeEditor.clearCurrentLine();
                container.setVisibility(View.GONE);
            });
        }
        if (btnPaste != null) {
            btnPaste.setOnClickListener(v -> {
                codeEditor.pasteCurrentLine();
                container.setVisibility(View.GONE);
            });
        }
    }

    private void setupExtraKeysBar() {
        LinearLayout container = findViewById(R.id.extra_keys_grid);
        if (container == null || codeEditor == null)
            return;
        container.removeAllViews();

        File propFile = getKeyPropertiesFile();
        if (!propFile.exists()) {
            copyDefaultKeyProperties(propFile);
        }
        List<List<String>> rows = loadKeyProperties(propFile);

        float density = getResources().getDisplayMetrics().density;
        int buttonHeight = (int) (38 * density);

        int rowPadding = (int) (2 * density);
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setPadding(rowPadding, rowPadding, rowPadding, rowPadding);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            for (int c = 0; c < row.size(); c++) {
                String label = row.get(c);
                Button btn = createKeyButton(label);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, buttonHeight, 1f);
                params.setMargins(1, 1, 1, 1);
                btn.setLayoutParams(params);

                rowLayout.addView(btn);
            }
            container.addView(rowLayout);
        }
    }

    private void toggleExtraKeysBar() {
        LinearLayout container = findViewById(R.id.extra_keys_grid);
        if (container == null)
            return;
        boolean visible = container.getVisibility() == View.VISIBLE;
        container.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void toggleOrientation() {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(isLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private File getKeyPropertiesFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        return new File(dir, "key.properties");
    }

    private Button createKeyButton(String label) {
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(getDisplayLabel(label));
        btn.setTextColor(ContextCompat.getColor(this, R.color.editor_text));
        btn.setTextSize(11f);
        btn.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        btn.setBackgroundResource(R.drawable.key_button_bg);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setPadding(2, 0, 2, 0);
        btn.setOnClickListener(v -> onExtraKeyPressed(label));
        return btn;
    }

    private String getDisplayLabel(String label) {
        switch (label.trim().toUpperCase()) {
            case "UP":
                return "↑";
            case "DOWN":
                return "↓";
            case "LEFT":
                return "←";
            case "RIGHT":
                return "→";
            case "HOME":
                return "◀";
            case "END":
                return "▶";
            case "PGUP":
                return "⇞";
            case "PGDN":
                return "⇟";
            case "ENTER":
                return "↵";
            case "TAB":
                return "⇥";
            case "ESC":
                return "Esc";
            default:
                return label;
        }
    }

    private void onExtraKeyPressed(String label) {
        if (codeEditor == null)
            return;
        codeEditor.requestFocus();

        String key = label.trim().toUpperCase();
        switch (key) {
            case "TAB":
                codeEditor.showAutoComplete();
                break;
            case "ENTER":
                codeEditor.insertText("\n");
                break;
            case "ESC":
                codeEditor.clearFocus();
                break;
            case "HOME":
                codeEditor.moveCursorToLineStart();
                break;
            case "END":
                codeEditor.moveCursorToLineEnd();
                break;
            case "LEFT":
                codeEditor.moveCursorLeft();
                break;
            case "RIGHT":
                codeEditor.moveCursorRight();
                break;
            case "UP":
                codeEditor.moveCursorUp();
                break;
            case "DOWN":
                codeEditor.moveCursorDown();
                break;
            case "PGUP":
                codeEditor.pageUp();
                break;
            case "PGDN":
                codeEditor.pageDown();
                break;
            case "BACKSLASH":
                codeEditor.insertText("\\");
                break;
            case "QUOTE":
                codeEditor.insertText("\"");
                break;
            case "()":
                codeEditor.insertText("()");
                break;
            case "{}":
                codeEditor.insertText("{}");
                break;
            case "[]":
                codeEditor.insertText("[]");
                break;
            case "CTRL":
            case "FN":
            case "ALT":
                // Modifier keys are not implemented in the on-screen keyboard.
                break;
            default:
                codeEditor.insertText(label);
                break;
        }
    }

    private List<List<String>> loadKeyProperties(File file) {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            StartupLog.log("Failed to load key.properties, using default: " + e.getMessage());
            Log.w(TAG, "Failed to load key.properties, using default", e);
            return parseKeyLayout(DEFAULT_EXTRA_KEYS);
        }
        String raw = props.getProperty("extra-keys", DEFAULT_EXTRA_KEYS);
        List<List<String>> rows = parseKeyLayout(raw);
        if (rows.isEmpty()) {
            return parseKeyLayout(DEFAULT_EXTRA_KEYS);
        }
        return rows;
    }

    private List<List<String>> parseKeyLayout(String raw) {
        List<List<String>> rows = new ArrayList<>();
        if (raw == null)
            return rows;

        int len = raw.length();
        int i = 0;
        int depth = 0; // 0 = outside, 1 = outer array, 2 = row array
        List<String> currentRow = null;

        while (i < len) {
            char c = raw.charAt(i);
            // Skip separators between tokens.
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }

            if (c == '[') {
                depth++;
                if (depth == 2) {
                    currentRow = new ArrayList<>();
                    rows.add(currentRow);
                }
                i++;
                continue;
            }

            if (c == ']') {
                if (depth > 0)
                    depth--;
                if (depth < 2) {
                    currentRow = null;
                }
                i++;
                continue;
            }

            if (depth == 2 && currentRow != null) {
                if (c == '\'' || c == '"') {
                    // Quoted key label, e.g. 'ESC'.
                    int end = i + 1;
                    while (end < len && raw.charAt(end) != c)
                        end++;
                    currentRow.add(raw.substring(i + 1, end));
                    i = end + 1;
                } else if (c == '(' || c == '{' || c == '[') {
                    // Bracket-enclosed token like (), {}, [].
                    char close = c == '(' ? ')' : (c == '{' ? '}' : ']');
                    int bracketDepth = 1;
                    int end = i + 1;
                    while (end < len && bracketDepth > 0) {
                        char ch = raw.charAt(end);
                        if (ch == c)
                            bracketDepth++;
                        else if (ch == close)
                            bracketDepth--;
                        end++;
                    }
                    currentRow.add(raw.substring(i, end));
                    i = end;
                } else {
                    // Unquoted plain token.
                    int end = i;
                    while (end < len) {
                        char ch = raw.charAt(end);
                        if (ch == ',' || ch == ']' || ch == ')' || ch == '}' || Character.isWhitespace(ch)) {
                            break;
                        }
                        end++;
                    }
                    currentRow.add(raw.substring(i, end));
                    i = end;
                }
            } else {
                // Anything outside a row is ignored.
                i++;
            }
        }

        return rows;
    }

    private void copyDefaultKeyProperties(File dest) {
        try (InputStream in = getAssets().open("key.properties");
                FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            Log.i(TAG, "Copied default key.properties to " + dest.getAbsolutePath());
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy default key.properties", e);
        }
    }

    private void ensureCustomSpritesFolder() {
        File customDir = new File(getExternalFilesDir(null), "sprites");
        if (!customDir.exists() && !customDir.mkdirs()) {
            Log.w(TAG, "Cannot create custom sprites folder: " + customDir);
            return;
        }
        String msg = getString(R.string.custom_sprites_path, customDir.getAbsolutePath());
        setStatus(msg, true);
    }

    private void saveCode(String code) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_LAST_CODE, code)
                .apply();
    }

    private String loadPersistedCode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_LAST_CODE, null);
    }

    private void setupKeyboardListener() {
        if (rootView == null || previewContainer == null)
            return;

        keyboardLayoutListener = () -> {
            Rect visibleFrame = new Rect();
            rootView.getWindowVisibleDisplayFrame(visibleFrame);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - visibleFrame.bottom;
            boolean open = keypadHeight > screenHeight * 0.15;
            if (open != keyboardVisible) {
                keyboardVisible = open;
                setPreviewWeight(keyboardVisible ? 0 : defaultPreviewWeight);
                if (previewView != null) {
                    if (keyboardVisible) {
                        previewView.onPause();
                    } else {
                        previewView.onResume();
                    }
                }
            }
        };
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);
    }

    private void setPreviewWeight(int weight) {
        if (previewContainer == null)
            return;
        ViewGroup.LayoutParams params = previewContainer.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
            lp.weight = weight;
            previewContainer.setLayoutParams(lp);
        }
    }

    private void setupStatusView() {
        if (statusView == null)
            return;
        statusView.setMovementMethod(new ScrollingMovementMethod());
        statusView.setOnClickListener(v -> {
            String text = statusView.getText().toString();
            if (text.isEmpty() || getString(R.string.status_ready).equals(text))
                return;
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.full_status_title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.copy, (dialog, which) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(
                                CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(getString(R.string.app_name), text));
                            Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        });
    }

    private void initPreview() {
        long start = System.nanoTime();
        // Initialize Arc preview surface using this AndroidApplication as host.
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.disableAudio = true;
        // Do not use wakelock; the editor does not need to keep the screen on.
        previewView.initialize(this, config);
        // Restore the previously compiled effect after a configuration change.
        if (compiledEffect != null) {
            previewView.setEffect(compiledEffect);
        }
        StartupLog.logTime("initPreview finished", start);
    }

    private void rebindViews() {
        try {
            // Preserve user code before reinflating the layout.
            if (codeEditor != null) {
                savedCode = codeEditor.getText().toString();
            }
            // Drop the old worker pool; a new one is created during rebind.
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            setContentView(R.layout.activity_main);
            bindViewsAndButtons();
            initPreview();
        } catch (Throwable t) {
            StartupLog.log("Fatal error during orientation rebind: " + t.getMessage());
            Log.e(TAG, "Fatal error during orientation rebind", t);
            Toast.makeText(this, "界面重建失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            throw t;
        }
    }

    private void compileAndRun() {
        String source = codeEditor.getText().toString();
        codeEditor.clearErrorLines(); // ← 新增：每次编译前清空旧红标
        setStatus("编译中...", true);
        executor.execute(() -> {
            try {
                Effect effect = EffectCompiler.compile(source, this);
                compiledEffect = effect;
                uiHandler.post(() -> {
                    previewView.reloadAtlas();
                    previewView.setEffect(effect);
                });
            } catch (Throwable ex) {
                Log.e(TAG, "Compilation failed", ex);
                String msg = ex.getMessage();

                // ← 新增：解析错误行号并标红
                Set<Integer> errorLines = parseErrorLines(msg);
                uiHandler.post(() -> codeEditor.setErrorLines(errorLines));

                if (msg != null && msg.startsWith(getString(R.string.compile_error) + ":")) {
                    uiHandler.post(() -> setStatus(msg, false));
                } else {
                    uiHandler.post(() -> setStatus(getString(R.string.compile_error) + ": " + msg, false));
                }
            }
        });
    }

    /** 从 EffectCompiler 抛出的中文异常信息中提取 "第 X 行:" 里的行号 */
    private Set<Integer> parseErrorLines(String errorMessage) {
        Set<Integer> lines = new HashSet<>();
        if (errorMessage == null)
            return lines;
        Pattern pattern = Pattern.compile("第\\s+(\\d+)\\s+行:");
        Matcher matcher = pattern.matcher(errorMessage);
        while (matcher.find()) {
            try {
                lines.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return lines;
    }

    /**
     * Restarts the application by scheduling a fresh launcher Intent via
     * {@link AlarmManager} and then killing the current process. The AlarmManager
     * path is required because calling {@code Process.killProcess} directly would
     * cancel any in-flight {@code startActivity} request scheduled in the same
     * process. By handing the new-launch intent to the system alarm service, the
     * launch survives the process death and starts MainActivity in a brand new
     * process so all GL/atlas/editor state is reinitialised from scratch.
     */
    private void restartApp() {
        try {
            if (codeEditor != null) {
                saveCode(codeEditor.getText().toString());
            }
        } catch (Throwable ignored) {
        }

        // 使用标准的 Intent 重启，而不是 AlarmManager + KillProcess
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            // 添加标志位：清除当前任务栈，重新开始
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);

            // 可选：添加一个淡入淡出动画，体验更好
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            // 如果获取不到启动 Intent，才降级使用原来的暴力方法（或者只调用 recreate()）
            recreate();
        }
    }

    private void exportCode() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_STORAGE);
            return;
        }
        doExport();
    }

    private void doExport() {
        String source = codeEditor.getText().toString();
        executor.execute(() -> {
            File dir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? getExternalFilesDir(null)
                    : android.os.Environment
                            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                uiHandler.post(() -> setStatus("导出失败: 无法获取目录", false));
                return;
            }
            File out = new File(dir, "Effect_" + System.currentTimeMillis() + ".java");
            try (FileWriter writer = new FileWriter(out)) {
                writer.write(wrapExport(source));
                uiHandler.post(() -> {
                    setStatus(getString(R.string.export_ok) + ": " + out.getAbsolutePath(), true);
                    Toast.makeText(this, R.string.export_ok, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException ex) {
                uiHandler.post(() -> setStatus("导出失败: " + ex.getMessage(), false));
            }
        });
    }

    private String wrapExport(String userCode) {
        String code = userCode.trim();

        // Extract user-written import statements first so they sit at the top of the
        // file.
        List<String> userImports = new ArrayList<>();
        Pattern importPattern = Pattern.compile("^[ \\t]*import[ \\t]+([^;]+);", Pattern.MULTILINE);
        Matcher matcher = importPattern.matcher(code);
        while (matcher.find()) {
            userImports.add(matcher.group(0));
        }
        code = matcher.replaceAll("").trim();

        // Users may declare helper fields/methods before the Effect expression.
        // Split the body at the first top-level "new Effect(" so fields/methods go
        // into the class body while the Effect itself becomes the field initializer.
        int effectStart = findTopLevelNewEffect(code);
        String userFields = "";
        String effectExpr = code;
        if (effectStart >= 0) {
            userFields = code.substring(0, effectStart).trim();
            effectExpr = code.substring(effectStart).trim();
        }

        if (!effectExpr.endsWith(";")) {
            effectExpr = effectExpr + ";";
        }

        // Build the import block from only the imports the user explicitly wrote.
        // The sample code now contains all required imports, so no hidden defaults
        // are injected, avoiding duplicate or conflicting imports in the export.
        StringBuilder imports = new StringBuilder();
        for (String imp : userImports) {
            imports.append(imp).append("\n");
        }

        // Indent user helper fields to class-body level.
        StringBuilder fields = new StringBuilder();
        if (!userFields.isEmpty()) {
            for (String line : userFields.split("\n")) {
                if (line.trim().isEmpty()) {
                    fields.append("\n");
                } else {
                    fields.append("    ").append(line).append("\n");
                }
            }
        }

        return "package effect.exported;\n\n" +
                imports.toString() + "\n" +
                "public class ExportedEffect {\n" +
                (fields.length() > 0 ? fields.toString() + "\n" : "") +
                "    public static final Effect effect = " + effectExpr + "\n" +
                "}\n";
    }

    /**
     * Returns the index of the first top-level {@code new Effect(} in {@code code},
     * skipping strings and comments and tracking brace nesting. Returns -1 if no
     * top-level Effect expression is found.
     */
    private static int findTopLevelNewEffect(String code) {
        int i = 0;
        int len = code.length();
        int braceDepth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        while (i < len - 10) { // "new Effect(" is 11 chars
            char c = code.charAt(i);

            if (inLineComment) {
                if (c == '\n')
                    inLineComment = false;
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < len && code.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < len) {
                    i += 2;
                } else {
                    if (c == stringChar)
                        inString = false;
                    i++;
                }
                continue;
            }

            if (c == '/' && i + 1 < len) {
                char next = code.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i += 2;
                    continue;
                } else if (next == '*') {
                    inBlockComment = true;
                    i += 2;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                i++;
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                braceDepth++;
                i++;
                continue;
            }
            if (c == '}' || c == ')' || c == ']') {
                braceDepth--;
                i++;
                continue;
            }

            if (braceDepth == 0 && code.startsWith("new Effect", i)) {
                int after = i + 10;
                if (after >= len || !Character.isJavaIdentifierPart(code.charAt(after))) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doExport();
        }
    }

    private void setStatus(String message, boolean success) {
        if (!success && message != null) {
            SpannableString spannable = new SpannableString(message);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("第\\s+(\\d+)\\s+行[:：]");
            java.util.regex.Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                final int lineNum;
                try {
                    lineNum = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                int start = matcher.start();
                int end = matcher.end();
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        if (codeEditor != null) {
                            codeEditor.goToLine(lineNum);
                        }
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setUnderlineText(true);
                    }
                }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            statusView.setText(spannable);
            statusView.setMovementMethod(LinkMovementMethod.getInstance());
        } else {
            statusView.setText(message);
            statusView.setMovementMethod(null);
        }

        // 保持你原来的颜色逻辑，下面只是示例，按你实际代码保留
        statusView.setTextColor(success
                ? getResources().getColor(android.R.color.holo_green_dark)
                : getResources().getColor(android.R.color.holo_red_dark));
    }

    private void removeKeyboardListener() {
        if (rootView != null && keyboardLayoutListener != null) {
            rootView.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
            keyboardLayoutListener = null;
        }
    }

    // Explicitly override all Application interface default methods.
    // Some Android 16 / ART versions fail to resolve interface default methods
    // when the parent class (AndroidApplication) does not redeclare them,
    // causing AbstractMethodError at runtime.

    @Override
    public void addListener(ApplicationListener listener) {
        synchronized (getListeners()) {
            getListeners().add(listener);
        }
    }

    @Override
    public void removeListener(ApplicationListener listener) {
        post(() -> {
            synchronized (getListeners()) {
                getListeners().remove(listener);
            }
        });
    }

    @Override
    public void defaultUpdate() {
        Core.settings.autosave();
        Time.updateGlobal();
    }

    @Override
    public boolean isDesktop() {
        return getType() == ApplicationType.desktop;
    }

    @Override
    public boolean isHeadless() {
        return getType() == ApplicationType.headless;
    }

    @Override
    public boolean isAndroid() {
        return getType() == ApplicationType.android;
    }

    @Override
    public boolean isIOS() {
        return getType() == ApplicationType.iOS;
    }

    @Override
    public boolean isMobile() {
        return isAndroid() || isIOS();
    }

    @Override
    public boolean isWeb() {
        return getType() == ApplicationType.web;
    }

    @Override
    public long getJavaHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    @Override
    public void dispose() {
        // flush any changes to settings upon dispose
        if (Core.settings != null) {
            Core.settings.autosave();
        }

        if (Core.audio != null) {
            Core.audio.dispose();
        }
    }

    @Override
    public boolean isOnMainThread() {
        Thread thread = getMainThread();
        return thread == null || Thread.currentThread() == thread;
    }
}
