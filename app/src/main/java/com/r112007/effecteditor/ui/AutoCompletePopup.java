package com.r112007.effecteditor.ui;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.r112007.effecteditor.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * IDE-style auto-completion popup for the Effect editor.
 * <p>
 * Shows a floating two-column list: completion label + icon on the left,
 * type/package detail on the right. Data comes from {@link CompletionEngine}.
 */
public class AutoCompletePopup {

    private static final int MAX_VISIBLE_ITEMS = 8;

    private final PopupWindow popup;
    private final ListView listView;
    private final CompletionAdapter adapter;
    private final List<CompletionItem> items = new ArrayList<>();
    private OnCompletionSelectedListener listener;

    public interface OnCompletionSelectedListener {
        void onCompletionSelected(String replacement, String importStmt, int cursorOffset);
    }

    public AutoCompletePopup(android.content.Context context) {
        listView = new ListView(context);
        listView.setBackgroundColor(Color.parseColor("#FF1A1B26"));
        listView.setDivider(new ColorDrawable(Color.parseColor("#FF24283B")));
        listView.setDividerHeight(1);
        listView.setVerticalScrollBarEnabled(false);
        listView.setHorizontalScrollBarEnabled(false);

        adapter = new CompletionAdapter();
        listView.setAdapter(adapter);

        popup = new PopupWindow(listView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setFocusable(false);
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) {
                CompletionItem item = items.get(position);
                CompletionEngine.recordUsage(item.label);
                if (listener != null) {
                    listener.onCompletionSelected(item.insert, item.importStmt, item.cursorOffset);
                }
            }
            dismiss();
        });
    }

    public void setOnCompletionSelectedListener(OnCompletionSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * Shows the popup floating near the cursor position.
     *
     * @param anchor     the view to anchor to (usually the editor)
     * @param prefix     text from the start of the current line to the cursor
     * @param fullText   the entire editor content
     * @param cursorPos  absolute cursor position in {@code fullText}
     * @param cursorX    x coordinate of the cursor inside the anchor view
     * @param cursorY    y coordinate of the cursor baseline inside the anchor view
     * @param lineHeight height of one text line, used to place the popup below the cursor
     */
    public void show(View anchor, CharSequence prefix, CharSequence fullText, int cursorPos,
                     int cursorX, int cursorY, int lineHeight) {
        String whole = fullText == null ? "" : fullText.toString();
        items.clear();

        CompletionEngine engine = CompletionEngine.get();
        List<CompletionEngine.Suggestion> suggestions = (engine != null && engine.isReady())
                ? engine.complete(whole, cursorPos)
                : Collections.emptyList();

        for (CompletionEngine.Suggestion s : suggestions) {
            items.add(new CompletionItem(s.label, s.insert, s.importStmt, s.priority,
                    s.cursorOffset, s.detail, s.typeText));
        }

        if (items.isEmpty()) {
            popup.dismiss();
            return;
        }

        String query = extractQuery(prefix == null ? "" : prefix.toString());
        sortItems(query);
        adapter.notifyDataSetChanged();

        // Size the popup like an IDE completion window (sizes are in dp).
        DisplayMetrics dm = anchor.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        Rect visibleFrame = new Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(visibleFrame);
        int usableHeight = visibleFrame.bottom;

        int anchorWidth = anchor.getWidth();
        int minWidth = dp(dm, 160);
        int maxWidth = dp(dm, 280);
        int desiredWidth = Math.min(Math.max(anchorWidth - dp(dm, 32), minWidth), maxWidth);
        int itemHeightEstimate = dp(dm, 34);
        int desiredHeight = Math.min(items.size(), MAX_VISIBLE_ITEMS) * itemHeightEstimate + dp(dm, 10);

        popup.setWidth(desiredWidth);
        popup.setHeight(desiredHeight);

        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int absX = anchorLoc[0] + cursorX;
        int belowY = anchorLoc[1] + cursorY + lineHeight + dp(dm, 4);
        int aboveY = anchorLoc[1] + cursorY - desiredHeight - dp(dm, 4);

        // Prefer below the cursor; flip above if it would fall under the keyboard.
        int absY = belowY;
        if (belowY + desiredHeight > usableHeight) {
            absY = aboveY;
        }
        // Keep horizontally within the screen.
        if (absX + desiredWidth > screenWidth) {
            absX = screenWidth - desiredWidth - dp(dm, 8);
        }
        if (absX < 0) {
            absX = dp(dm, 8);
        }
        if (absY < visibleFrame.top) {
            absY = visibleFrame.top + dp(dm, 8);
        }
        if (absY + desiredHeight > usableHeight) {
            absY = usableHeight - desiredHeight - dp(dm, 8);
        }

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, absX, absY);
        listView.setSelection(0);
    }

    public void dismiss() {
        if (popup.isShowing()) popup.dismiss();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    /**
     * Inserts the currently highlighted completion, if any. Used for Tab-key commit.
     */
    public void commitSelected() {
        if (!popup.isShowing() || items.isEmpty()) return;
        int position = listView.getSelectedItemPosition();
        if (position < 0 || position >= items.size()) {
            position = 0;
        }
        CompletionItem item = items.get(position);
        CompletionEngine.recordUsage(item.label);
        if (listener != null) {
            listener.onCompletionSelected(item.insert, item.importStmt, item.cursorOffset);
        }
        dismiss();
    }

    private void sortItems(String query) {
        Collections.sort(items, (a, b) -> Integer.compare(b.priority, a.priority));
    }

    private int dp(DisplayMetrics dm, int dp) {
        return (int) (dp * dm.density + 0.5f);
    }

    private String extractQuery(String text) {
        int end = text.length();
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '"') {
                start--;
            } else {
                break;
            }
        }
        return text.substring(start, end);
    }

    private String kindLetter(String detail) {
        if (detail == null || detail.isEmpty()) return "?";
        switch (detail.toLowerCase(Locale.ROOT)) {
            case "class":
            case "interface":
                return "C";
            case "method":
                return "M";
            case "field":
                return "F";
            case "variable":
                return "V";
            case "keyword":
                return "K";
            case "function":
                return "ƒ";
            case "package":
                return "P";
            case "snippet":
                return "S";
            default:
                return detail.substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }

    private int kindColor(String detail) {
        if (detail == null) return Color.parseColor("#FF7AA2F7");
        switch (detail.toLowerCase(Locale.ROOT)) {
            case "class":
            case "interface":
                return Color.parseColor("#FFE0AF68");
            case "method":
            case "function":
                return Color.parseColor("#FF7AA2F7");
            case "field":
                return Color.parseColor("#FFBB9AF7");
            case "variable":
                return Color.parseColor("#FF7AA2F7");
            case "keyword":
                return Color.parseColor("#FFF7768E");
            case "package":
                return Color.parseColor("#FF9ECE6A");
            case "snippet":
                return Color.parseColor("#FF89DDFF");
            default:
                return Color.parseColor("#FF7AA2F7");
        }
    }

    private class CompletionAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public CompletionItem getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.completion_item, parent, false);
                holder = new ViewHolder();
                holder.kind = convertView.findViewById(R.id.tv_kind);
                holder.label = convertView.findViewById(R.id.tv_label);
                holder.type = convertView.findViewById(R.id.tv_type);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            CompletionItem item = getItem(position);
            holder.kind.setText(kindLetter(item.detail));
            holder.kind.setTextColor(kindColor(item.detail));
            holder.label.setText(item.label);
            holder.type.setText(item.typeText);
            holder.type.setVisibility(item.typeText.isEmpty() ? View.GONE : View.VISIBLE);

            // Highlight the selected row.
            convertView.setBackgroundColor(position == listView.getSelectedItemPosition()
                    ? Color.parseColor("#FF283457")
                    : Color.parseColor("#FF1A1B26"));

            return convertView;
        }
    }

    private static class ViewHolder {
        TextView kind;
        TextView label;
        TextView type;
    }

    private static class CompletionItem {
        final String label;
        final String insert;
        final String importStmt;
        final int priority;
        final int cursorOffset;
        final String detail;
        final String typeText;

        CompletionItem(String label, String insert, String importStmt, int priority,
                       int cursorOffset, String detail, String typeText) {
            this.label = label;
            this.insert = insert;
            this.importStmt = importStmt;
            this.priority = priority;
            this.cursorOffset = cursorOffset;
            this.detail = detail;
            this.typeText = typeText;
        }
    }
}
