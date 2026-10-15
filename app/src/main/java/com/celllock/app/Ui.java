package com.celllock.app;

import android.content.Context;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;

/** 界面配色与控件工厂。所有页面都用代码构建，保证风格一致。 */
final class Ui {
    static final int BG = 0xfff3f5f2;
    static final int CARD = 0xffffffff;
    static final int INK = 0xff192b24;
    static final int MUTED = 0xff728078;
    static final int HINT = 0xffa2aca4;
    static final int LINE = 0xffe5ebe5;
    static final int TRACK = 0xffe8ede7;
    static final int GREEN = 0xff177557;
    static final int SOFT_GREEN = 0xffe7f2eb;
    static final int AMBER = 0xff946117;
    static final int SOFT_AMBER = 0xfffaf1df;
    static final int RED = 0xffb3403f;
    static final int SOFT_RED = 0xfffbe9e7;

    final Context context;
    private final float density;

    Ui(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    // ---------- 布局 ----------

    LinearLayout column() {
        LinearLayout v = new LinearLayout(context);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    LinearLayout row() {
        LinearLayout v = new LinearLayout(context);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    LinearLayout.LayoutParams fill(int heightDp) {
        return new LinearLayout.LayoutParams(-1, heightDp < 0 ? heightDp : dp(heightDp));
    }

    LinearLayout.LayoutParams weight(int heightDp) {
        return new LinearLayout.LayoutParams(0, heightDp < 0 ? heightDp : dp(heightDp), 1);
    }

    void gap(LinearLayout parent, int dp) {
        boolean vertical = parent.getOrientation() == LinearLayout.VERTICAL;
        parent.addView(new View(context), vertical
                ? new LinearLayout.LayoutParams(1, dp(dp))
                : new LinearLayout.LayoutParams(dp(dp), 1));
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(shape(CARD, 20, 0));
        parent.addView(card, fill(-2));
        return card;
    }

    View divider() {
        View line = new View(context);
        line.setBackgroundColor(LINE);
        return line;
    }

    AlertDialog dialog(String title, String subtitle, LinearLayout body, String action, Runnable apply, String cancel) {
        int maxHeight=Math.round(context.getResources().getDisplayMetrics().heightPixels*.84f);
        LinearLayout content=new LinearLayout(context) {
            @Override protected void onMeasure(int width,int height) { super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(Math.min(maxHeight,View.MeasureSpec.getSize(height)),View.MeasureSpec.AT_MOST)); }
        };
        content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(24),dp(24),dp(20));content.setBackground(shape(CARD,24,0));
        TextView heading=text(title,21,INK);heading.setTypeface(null,Typeface.BOLD);content.addView(heading,fill(-2));
        if(subtitle!=null&&!subtitle.isEmpty()){gap(content,7);content.addView(caption(subtitle),fill(-2));}
        gap(content,18);ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,-2,1));
        gap(content,18);LinearLayout footer=row();content.addView(footer,fill(-2));
        AlertDialog dialog=new AlertDialog.Builder(context).setView(content).create();
        if(cancel!=null){Button back=button(cancel,false);back.setMinHeight(dp(48));back.setPadding(dp(12),dp(10),dp(12),dp(10));back.setOnClickListener(v->dialog.dismiss());footer.addView(back,weight(-2));gap(footer,10);}
        Button primary=button(action,true);primary.setMinHeight(dp(48));primary.setPadding(dp(12),dp(10),dp(12),dp(10));primary.setOnClickListener(v->{dialog.dismiss();if(apply!=null)apply.run();});footer.addView(primary,weight(-2));
        dialog.setOnShowListener(ignored->{
            android.view.Window window=dialog.getWindow();if(window==null)return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.getDecorView().setPadding(0,0,0,0);
            window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels-dp(40),dp(440)),-2);
        });
        return dialog;
    }

    LinearLayout choice(LinearLayout parent,String title,String description,Runnable action) {
        LinearLayout choice=row();choice.setPadding(dp(15),dp(16),dp(15),dp(16));choice.setBackground(ripple(SOFT_GREEN,16));
        LinearLayout labels=column();TextView name=text(title,16,INK);name.setTypeface(null,Typeface.BOLD);labels.addView(name);gap(labels,6);labels.addView(caption(description));choice.addView(labels,weight(-2));gap(choice,10);choice.addView(text("›",24,GREEN));
        choice.setOnClickListener(v->action.run());choice.setFocusable(true);choice.setContentDescription(title+"，"+description);parent.addView(choice,fill(-2));return choice;
    }

    // ---------- 文字 ----------

    TextView text(String value, float sp, int color) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(3), 1);
        return t;
    }

    TextView title(String value) {
        TextView t = text(value, 16, INK);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    TextView caption(String value) {
        TextView t = text(value, 12, MUTED);
        t.setLineSpacing(dp(4), 1);
        return t;
    }

    TextView pill(String label, int color, int background) {
        TextView p = text(label, 11, color);
        p.setTypeface(null, Typeface.BOLD);
        p.setPadding(dp(10), dp(6), dp(10), dp(6));
        p.setBackground(shape(background, 8, 0));
        return p;
    }

    /** 左侧名称、右侧取值的一行，返回取值控件。 */
    TextView settingRow(LinearLayout parent, String label, String value) {
        LinearLayout line = row();
        line.setPadding(0, dp(9), 0, dp(9));
        line.addView(text(label, 14, INK), weight(-2));
        TextView setting = text(value, 13, MUTED);
        setting.setGravity(Gravity.END);
        line.addView(setting);
        parent.addView(line, fill(-2));
        return setting;
    }

    // ---------- 按钮与输入 ----------

    GradientDrawable shape(int color, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    RippleDrawable ripple(int color, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x16177557), shape(color, radiusDp, 0), shape(CARD, radiusDp, 0));
    }

    Button button(String label, boolean primary) {
        return button(label, primary ? CARD : GREEN, primary ? GREEN : SOFT_GREEN);
    }

    Button dangerButton(String label) {
        return button(label, RED, SOFT_RED);
    }

    private Button button(String label, int textColor, int background) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(ripple(background, 14));
        return b;
    }

    static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .45f);
    }

    EditText numberInput(String hint) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setHintTextColor(HINT);
        input.setTextSize(19);
        input.setPadding(dp(13), dp(8), dp(10), dp(8));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(hint);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, shape(CARD, 12, GREEN));
        bg.addState(new int[]{}, shape(BG, 12, 0));
        input.setBackground(bg);
        return input;
    }

    /** 带标题的输入框：标题在上，输入框在下。 */
    EditText labeledInput(LinearLayout parent, String label, String hint) {
        LinearLayout col = column();
        col.addView(text(label, 12, MUTED));
        gap(col, 8);
        EditText input = numberInput(hint);
        col.addView(input, fill(52));
        parent.addView(col, weight(-2));
        return input;
    }

    Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(context);
        spinner.setAdapter(adapter(values));
        spinner.setBackgroundTintList(ColorStateList.valueOf(MUTED));
        return spinner;
    }

    ArrayAdapter<String> adapter(String[] values) {
        return new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convert, parent);
                v.setTextColor(INK);
                v.setTextSize(14);
                v.setSingleLine(true);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setPadding(dp(8), 0, dp(2), 0);
                return v;
            }

            @Override public View getDropDownView(int position, View convert, ViewGroup parent) {
                TextView v = new TextView(context);
                v.setText(getItem(position));
                v.setTextColor(INK);
                v.setTextSize(15);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(18), dp(15), dp(18), dp(15));
                v.setBackgroundColor(CARD);
                return v;
            }
        };
    }

    // ---------- 分段选择 ----------

    interface OnSelect {
        void onSelect(int index);
    }

    /** 胶囊式分段选择器，比如“LTE / 5G NR”。 */
    final class Segmented extends LinearLayout {
        private final Button[] items;
        private int selected = -1;

        Segmented(String[] labels, OnSelect listener) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(4), dp(4), dp(4), dp(4));
            setBackground(shape(TRACK, 14, 0));
            items = new Button[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                Button b = button(labels[i], false);
                b.setTextSize(13);
                b.setOnClickListener(v -> {
                    if (index == selected) return;
                    select(index);
                    listener.onSelect(index);
                });
                items[i] = b;
                addView(b, new LayoutParams(0, dp(40), 1));
            }
            select(0);
        }

        void select(int index) {
            selected = index;
            for (int i = 0; i < items.length; i++) {
                boolean on = i == index;
                items[i].setBackground(ripple(on ? CARD : TRACK, 11));
                items[i].setTextColor(on ? GREEN : MUTED);
                items[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            }
        }

        int selected() {
            return selected;
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            for (Button b : items) b.setEnabled(enabled);
            setAlpha(enabled ? 1f : .5f);
        }
    }

    // ---------- 频段芯片 ----------

    /** 可多选的两行芯片：上面是频段号，下面是频率提示。 */
    TextView chip(String label, String detail) {
        TextView chip = new TextView(context);
        SpannableStringBuilder text = new SpannableStringBuilder(label);
        if (detail != null && !detail.isEmpty()) {
            int start = text.length();
            text.append('\n').append(detail);
            text.setSpan(new RelativeSizeSpan(.72f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(14);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setIncludeFontPadding(false);
        chip.setLineSpacing(dp(2), 1);
        chip.setMinWidth(dp(62));
        chip.setPadding(dp(10), dp(8), dp(10), dp(8));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_selected}, shape(SOFT_GREEN, 12, GREEN));
        bg.addState(new int[]{}, shape(BG, 12, 0));
        chip.setBackground(bg);
        chip.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{GREEN, MUTED}));
        chip.setOnClickListener(v -> v.setSelected(!v.isSelected()));
        return chip;
    }
}
