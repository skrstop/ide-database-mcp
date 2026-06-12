package com.skrstop.ide.databasemcp.toolwindow;

import com.intellij.ui.JBColor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 Toggle Switch 组件，类似 iOS / Material Design 的滑动开关。
 * 提供 {@link #isSelected()} / {@link #setSelected(boolean)} 和 {@link #addActionListener(ActionListener)} 接口，
 * 与 JCheckBox / JToggleButton 的使用方式兼容。
 */
public final class ToggleSwitch extends JComponent {
    private static final int TRACK_WIDTH = 36;
    private static final int TRACK_HEIGHT = 20;
    private static final int THUMB_DIAMETER = 16;
    private static final int THUMB_PADDING = 2;

    private static final Color COLOR_TRACK_OFF = new JBColor(new Color(0xBDBDBD), new Color(0x666666));
    private static final Color COLOR_TRACK_ON = new JBColor(new Color(0x1A73E8), new Color(0x8AB4F8));
    private static final Color COLOR_THUMB = new JBColor(Color.WHITE, new Color(0xEEEEEE));
    private static final Color COLOR_TRACK_DISABLED = new JBColor(new Color(0xE0E0E0), new Color(0x444444));
    private static final Color COLOR_THUMB_DISABLED = new JBColor(new Color(0xCCCCCC), new Color(0x555555));

    private boolean selected = false;
    private boolean animate = false;
    private float thumbPosition; // 0.0 (off) ~ 1.0 (on)
    private Timer animationTimer;
    private final List<ActionListener> actionListeners = new ArrayList<>();

    public ToggleSwitch() {
        this(false);
    }

    public ToggleSwitch(boolean selected) {
        this.selected = selected;
        this.thumbPosition = selected ? 1.0f : 0.0f;
        setPreferredSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setMinimumSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (isEnabled()) {
                    toggle();
                }
            }
        });
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        if (this.selected != selected) {
            this.selected = selected;
            animateTransition();
            fireActionEvent();
        }
    }

    private void toggle() {
        selected = !selected;
        animateTransition();
        fireActionEvent();
    }

    private void animateTransition() {
        if (!isShowing()) {
            thumbPosition = selected ? 1.0f : 0.0f;
            repaint();
            return;
        }
        if (animationTimer != null && animationTimer.isRunning()) {
            animationTimer.stop();
        }
        float target = selected ? 1.0f : 0.0f;
        float step = selected ? 0.08f : -0.08f;
        animationTimer = new Timer(16, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                thumbPosition += step;
                if ((step > 0 && thumbPosition >= target) || (step < 0 && thumbPosition <= target)) {
                    thumbPosition = target;
                    animationTimer.stop();
                }
                repaint();
            }
        });
        animationTimer.start();
    }

    public void addActionListener(ActionListener listener) {
        actionListeners.add(listener);
    }

    public void removeActionListener(ActionListener listener) {
        actionListeners.remove(listener);
    }

    private void fireActionEvent() {
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED,
                selected ? "SELECTED" : "DESELECTED", System.currentTimeMillis(), 0);
        for (ActionListener listener : actionListeners) {
            listener.actionPerformed(event);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int trackW = getWidth();
        int trackH = TRACK_HEIGHT;
        int trackY = (getHeight() - trackH) / 2;

        // 轨道
        Color trackColor;
        if (!isEnabled()) {
            trackColor = COLOR_TRACK_DISABLED;
        } else {
            trackColor = selected ? COLOR_TRACK_ON : COLOR_TRACK_OFF;
        }
        g2.setColor(trackColor);
        g2.fillRoundRect(0, trackY, trackW, trackH, trackH, trackH);

        // 滑块
        int thumbX = THUMB_PADDING + Math.round(thumbPosition * (trackW - THUMB_DIAMETER - THUMB_PADDING * 2));
        int thumbY = trackY + (trackH - THUMB_DIAMETER) / 2;
        g2.setColor(isEnabled() ? COLOR_THUMB : COLOR_THUMB_DISABLED);
        g2.fillOval(thumbX, thumbY, THUMB_DIAMETER, THUMB_DIAMETER);

        // 滑块边框阴影
        if (isEnabled()) {
            g2.setColor(new Color(0, 0, 0, 30));
            g2.drawOval(thumbX, thumbY, THUMB_DIAMETER, THUMB_DIAMETER);
        }

        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(TRACK_WIDTH, TRACK_HEIGHT);
    }
}
