/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.graphics.ColorUtils;
import android.util.AttributeSet;
import android.util.Property;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewParent;
import android.widget.TextView;

import com.android.launcher3.IconCache.IconLoadRequest;
import com.android.launcher3.IconCache.ItemInfoUpdateReceiver;
import com.android.launcher3.badge.BadgeInfo;
import com.android.launcher3.badge.BadgeRenderer;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.graphics.DrawableFactory;
import com.android.launcher3.graphics.HolographicOutlineHelper;
import com.android.launcher3.graphics.IconPalette;
import com.android.launcher3.graphics.PreloadIconDrawable;
import com.android.launcher3.model.PackageItemInfo;
import com.google.android.apps.nexuslauncher.R;

import java.text.NumberFormat;

/**
 * TextView that draws a bubble behind the text. We cannot use a LineBackgroundSpan
 * because we want to make the bubble taller than the text and TextView's clip is
 * too aggressive.
 */
@SuppressLint("AppCompatCustomView")
public class BubbleTextView extends TextView implements ItemInfoUpdateReceiver {

    // Dimensions in DP
    private static final float AMBIENT_SHADOW_RADIUS = 2.5f;
    private static final float KEY_SHADOW_RADIUS = 1f;
    private static final float KEY_SHADOW_OFFSET = 0.5f;
    private static final int AMBIENT_SHADOW_COLOR = 0x33000000;
    private static final int KEY_SHADOW_COLOR = 0x66000000;

    private static final int DISPLAY_WORKSPACE = 0;
    private static final int DISPLAY_ALL_APPS = 1;
    private static final int DISPLAY_FOLDER = 2;

    private static final int[] STATE_PRESSED = new int[] {android.R.attr.state_pressed};

    private final Launcher mLauncher;
    private Drawable mIcon;
    private final boolean mCenterVertically;
    private final CheckLongPressHelper mLongPressHelper;
    private final HolographicOutlineHelper mOutlineHelper;
    private final StylusEventHelper mStylusEventHelper;

    private boolean mBackgroundSizeChanged;

    private Bitmap mPressedBackground;

    private float mSlop;

    private final boolean mDeferShadowGenerationOnTouch;
    private final boolean mLayoutHorizontal;
    private final int mIconSize;
    @ViewDebug.ExportedProperty(category = "launcher")
    private int mTextColor;
    private boolean mIsIconVisible;

    private BadgeInfo mBadgeInfo;
    private BadgeRenderer mBadgeRenderer;
    private IconPalette mBadgePalette;
    private float mBadgeScale;
    private boolean mForceHideBadge;
    private Point mTempSpaceForBadgeOffset = new Point();
    private Rect mTempIconBounds = new Rect();

    private static final Property<BubbleTextView, Float> BADGE_SCALE_PROPERTY
            = new Property<BubbleTextView, Float>(Float.TYPE, "badgeScale") {
        @Override
        public Float get(BubbleTextView bubbleTextView) {
            return bubbleTextView.mBadgeScale;
        }

        @Override
        public void set(BubbleTextView bubbleTextView, Float value) {
            bubbleTextView.mBadgeScale = value;
            bubbleTextView.invalidate();
        }
    };

    public static final Property TEXT_ALPHA_PROPERTY
            = new Property<BubbleTextView, Integer>(Integer.TYPE, "textAlpha") {
        @Override
        public Integer get(BubbleTextView bubbleTextView) {
            return bubbleTextView.getTextAlpha();
        }

        @Override
        public void set(BubbleTextView bubbleTextView, Integer value) {
            bubbleTextView.setTextAlpha(value);
        }
    };

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mStayPressed;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mIgnorePressedStateChange;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mDisableRelayout = false;

    private IconLoadRequest mIconLoadRequest;

    public BubbleTextView(Context context) {
        this(context, null, 0);
    }

    public BubbleTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mIsIconVisible = true;
        mLauncher = Launcher.getLauncher(context);
        DeviceProfile grid = mLauncher.getDeviceProfile();
        mSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.BubbleTextView, defStyle, 0);
        mLayoutHorizontal = a.getBoolean(R.styleable.BubbleTextView_layoutHorizontal, false);
        mDeferShadowGenerationOnTouch =
                a.getBoolean(R.styleable.BubbleTextView_deferShadowGeneration, false);

        int display = a.getInteger(R.styleable.BubbleTextView_iconDisplay, DISPLAY_WORKSPACE);
        int defaultIconSize = grid.iconSizePx;
        if (display == DISPLAY_WORKSPACE) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, grid.iconTextSizePx);
            setCompoundDrawablePadding(grid.iconDrawablePaddingPx);
        } else if (display == DISPLAY_ALL_APPS) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, grid.allAppsIconTextSizePx);
            setCompoundDrawablePadding(grid.allAppsIconDrawablePaddingPx);
            defaultIconSize = grid.allAppsIconSizePx;
        } else if (display == DISPLAY_FOLDER) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, grid.folderChildTextSizePx);
            setCompoundDrawablePadding(grid.folderChildDrawablePaddingPx);
            defaultIconSize = grid.folderChildIconSizePx;
        }
        mCenterVertically = a.getBoolean(R.styleable.BubbleTextView_centerVertically, false);

        mIconSize = a.getDimensionPixelSize(R.styleable.BubbleTextView_iconSizeOverride,
                defaultIconSize);
        a.recycle();

        mLongPressHelper = new CheckLongPressHelper(this);
        mStylusEventHelper = new StylusEventHelper(new SimpleOnStylusPressListener(this), this);

        mOutlineHelper = HolographicOutlineHelper.getInstance(getContext());
        setAccessibilityDelegate(mLauncher.getAccessibilityDelegate());
    }

    public void applyFromShortcutInfo(ShortcutInfo info) {
        applyFromShortcutInfo(info, false);
    }

    public void applyFromShortcutInfo(ShortcutInfo info, boolean promiseStateChanged) {
        applyIconAndLabel(info.iconBitmap, info);
        setTag(info);
        if (promiseStateChanged || info.isPromise()) {
            applyPromiseState(promiseStateChanged);
        }

        applyBadgeState(info, false /* animate */);
    }

    public void applyFromApplicationInfo(AppInfo info) {
        applyIconAndLabel(info.iconBitmap, info);

        // We don't need to check the info since it's not a ShortcutInfo
        super.setTag(info);

        // Verify high res immediately
        verifyHighRes();

        if (info instanceof PromiseAppInfo) {
            applyProgressLevel(((PromiseAppInfo)info).level);
        }

        applyBadgeState(info, false /* animate */);
    }

    public void applyFromPackageItemInfo(PackageItemInfo info) {
        applyIconAndLabel(info.iconBitmap, info);
        // We don't need to check the info since it's not a ShortcutInfo
        super.setTag(info);

        // Verify high res immediately
        verifyHighRes();
    }

    private void applyIconAndLabel(Bitmap icon, ItemInfo info) {
        FastBitmapDrawable iconDrawable = DrawableFactory.get(getContext()).newIcon(icon, info);
        iconDrawable.setIsDisabled(info.isDisabled());
        setIcon(iconDrawable);
        setText(info.title);
        if (info.contentDescription != null) {
            setContentDescription(info.isDisabled()
                    ? getContext().getString(R.string.disabled_app_label, info.contentDescription)
                    : info.contentDescription);
        }
    }

    /**
     * Overrides the default long press timeout.
     */
    public void setLongPressTimeout(int longPressTimeout) {
        mLongPressHelper.setLongPressTimeout(longPressTimeout);
    }

    public void setIconVisible(boolean mIsIconVisible) {
        this.mIsIconVisible = mIsIconVisible;
        this.mDisableRelayout = true;
        Object mIcon = this.mIcon;
        if (!mIsIconVisible) {
            mIcon = new ColorDrawable(0);
            ((Drawable)mIcon).setBounds(0, 0, this.mIconSize, this.mIconSize);
        }
        this.applyCompoundDrawables((Drawable)mIcon);
        this.mDisableRelayout = false;
    }

    @Override
    public void setTag(Object tag) {
        if (tag != null) {
            LauncherModel.checkItemInfo((ItemInfo) tag);
        }
        super.setTag(tag);
    }

    @Override
    public void refreshDrawableState() {
        if (!mIgnorePressedStateChange) {
            super.refreshDrawableState();
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        if (mStayPressed) {
            mergeDrawableStates(drawableState, STATE_PRESSED);
        }
        return drawableState;
    }

    /** Returns the icon for this view. */
    public Drawable getIcon() {
        return mIcon;
    }

    /** Returns whether the layout is horizontal. */
    public boolean isLayoutHorizontal() {
        return mLayoutHorizontal;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Call the superclass onTouchEvent first, because sometimes it changes the state to
        // isPressed() on an ACTION_UP
        boolean result = super.onTouchEvent(event);

        // Check for a stylus button press, if it occurs cancel any long press checks.
        if (mStylusEventHelper.onMotionEvent(event)) {
            mLongPressHelper.cancelLongPress();
            result = true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // So that the pressed outline is visible immediately on setStayPressed(),
                // we pre-create it on ACTION_DOWN (it takes a small but perceptible amount of time
                // to create it)
                if (!mDeferShadowGenerationOnTouch && mPressedBackground == null) {
                    mPressedBackground = mOutlineHelper.createMediumDropShadow(this);
                }

                // If we're in a stylus button press, don't check for long press.
                if (!mStylusEventHelper.inStylusButtonPressed()) {
                    mLongPressHelper.postCheckForLongPress();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                // If we've touched down and up on an item, and it's still not "pressed", then
                // destroy the pressed outline
                if (!isPressed()) {
                    mPressedBackground = null;
                }

                mLongPressHelper.cancelLongPress();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!Utilities.pointInView(this, event.getX(), event.getY(), mSlop)) {
                    mLongPressHelper.cancelLongPress();
                }
                break;
        }
        return result;
    }

    void setStayPressed(boolean stayPressed) {
        mStayPressed = stayPressed;
        if (!stayPressed) {
            HolographicOutlineHelper.getInstance(getContext()).recycleShadowBitmap(mPressedBackground);
            mPressedBackground = null;
        } else {
            if (mPressedBackground == null) {
                mPressedBackground = mOutlineHelper.createMediumDropShadow(this);
            }
        }

        // Only show the shadow effect when persistent pressed state is set.
        ViewParent parent = getParent();
        if (parent != null && parent.getParent() instanceof BubbleTextShadowHandler) {
            ((BubbleTextShadowHandler) parent.getParent()).setPressedIcon(
                    this, mPressedBackground);
        }

        refreshDrawableState();
    }

    void clearPressedBackground() {
        setPressed(false);
        setStayPressed(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (super.onKeyDown(keyCode, event)) {
            // Pre-create shadow so show immediately on click.
            if (mPressedBackground == null) {
                mPressedBackground = mOutlineHelper.createMediumDropShadow(this);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Unlike touch events, keypress event propagate pressed state change immediately,
        // without waiting for onClickHandler to execute. Disable pressed state changes here
        // to avoid flickering.
        mIgnorePressedStateChange = true;
        boolean result = super.onKeyUp(keyCode, event);

        mPressedBackground = null;
        mIgnorePressedStateChange = false;
        refreshDrawableState();
        return result;
    }

    public ObjectAnimator createTextAlphaAnimator(final boolean b) {
        int alpha = (shouldTextBeVisible() && b) ? Color.alpha(this.mTextColor) : 0;
        return ObjectAnimator.ofInt(this, BubbleTextView.TEXT_ALPHA_PROPERTY, new int[] { alpha });
    }

    protected void drawBadgeIfNecessary(final Canvas canvas) {
        if (!this.mForceHideBadge && (this.hasBadge() || this.mBadgeScale > 0.0f)) {
            this.getIconBounds(this.mTempIconBounds);
            this.mTempSpaceForBadgeOffset.set((this.getWidth() - this.mIconSize) / 2, this.getPaddingTop());
            final int scrollX = this.getScrollX();
            final int scrollY = this.getScrollY();
            canvas.translate((float)scrollX, (float)scrollY);
            this.mBadgeRenderer.draw(canvas, this.mBadgePalette, this.mBadgeInfo, this.mTempIconBounds, this.mBadgeScale, this.mTempSpaceForBadgeOffset);
            canvas.translate((float)(-scrollX), (float)(-scrollY));
        }
    }

    @Override
    public void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        this.drawBadgeIfNecessary(canvas);
    }

    public boolean shouldTextBeVisible() {
        final boolean b = true;
        Object o;
        if (this.getParent() instanceof FolderIcon) {
            o = ((View)this.getParent()).getTag();
        }
        else {
            o = this.getTag();
        }
        ItemInfo itemInfo;
        if (o instanceof ItemInfo) {
            itemInfo = (ItemInfo)o;
        }
        else {
            itemInfo = null;
        }
        return (itemInfo == null || itemInfo.container != -101) && b;
    }

    private int getTextAlpha() {
        return Color.alpha(this.getCurrentTextColor());
    }

    @SuppressLint("WrongCall")
    protected void drawWithoutBadge(final Canvas canvas) {
        super.onDraw(canvas);
    }

    public void forceHideBadge(boolean forceHideBadge) {
        if (mForceHideBadge == forceHideBadge) {
            return;
        }
        mForceHideBadge = forceHideBadge;

        if (forceHideBadge) {
            invalidate();
        } else if (hasBadge()) {
            ObjectAnimator.ofFloat(this, BADGE_SCALE_PROPERTY, 0, 1).start();
        }
    }

    private boolean hasBadge() {
        return mBadgeInfo != null;
    }

    public void getIconBounds(Rect outBounds) {
        int top = getPaddingTop();
        int left = (getWidth() - mIconSize) / 2;
        int right = left + mIconSize;
        int bottom = top + mIconSize;
        outBounds.set(left, top, right, bottom);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mCenterVertically) {
            Paint.FontMetrics fm = getPaint().getFontMetrics();
            int cellHeightPx = mIconSize + getCompoundDrawablePadding() +
                    (int) Math.ceil(fm.bottom - fm.top);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setPadding(getPaddingLeft(), (height - cellHeightPx) / 2, getPaddingRight(),
                    getPaddingBottom());
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setTextColor(int color) {
        mTextColor = color;
        super.setTextColor(color);
    }

    @Override
    public void setTextColor(ColorStateList colors) {
        mTextColor = colors.getDefaultColor();
        super.setTextColor(colors);
    }

    public void setTextVisibility(boolean visible) {
        if (visible) {
            super.setTextColor(this.mTextColor);
        } else {
            this.setTextAlpha(0);
        }
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();

        mLongPressHelper.cancelLongPress();
    }

    public PreloadIconDrawable applyProgressLevel(final int n) {
        final int n2 = 1;
        if (this.getTag() instanceof ItemInfoWithIcon) {
            final ItemInfoWithIcon itemInfoWithIcon = (ItemInfoWithIcon)this.getTag();
            String contentDescription;
            if (n > 0) {
                final Context context = this.getContext();
                final Object[] array = { itemInfoWithIcon.title, null };
                array[n2] = NumberFormat.getPercentInstance().format(n * 0.01);
                contentDescription = context.getString(R.string.app_downloading_title, array);
            }
            else {
                final Context context2 = this.getContext();
                final Object[] array2 = new Object[n2];
                array2[0] = itemInfoWithIcon.title;
                contentDescription = context2.getString(R.string.app_waiting_download_title, array2);
            }
            this.setContentDescription(contentDescription);
            if (this.mIcon != null) {
                PreloadIconDrawable pendingIcon;
                if (this.mIcon instanceof PreloadIconDrawable) {
                    pendingIcon = (PreloadIconDrawable)this.mIcon;
                    pendingIcon.setLevel(n);
                }
                else {
                    pendingIcon = DrawableFactory.get(this.getContext()).newPendingIcon(itemInfoWithIcon.iconBitmap, this.getContext());
                    pendingIcon.setLevel(n);
                    this.setIcon(pendingIcon);
                }
                return pendingIcon;
            }
        }
        return null;
    }

    public void applyPromiseState(boolean promiseStateChanged) {
        if (getTag() instanceof ShortcutInfo) {
            ShortcutInfo info = (ShortcutInfo)this.getTag();
            boolean isPromise = info.isPromise();
            int progressLevel = isPromise ?
                    ((info.hasStatusFlag(ShortcutInfo.FLAG_INSTALL_SESSION_ACTIVE) ?
                            info.getInstallProgress() : 0)) : 100;

            PreloadIconDrawable applyProgressLevel = this.applyProgressLevel(progressLevel);
            if (applyProgressLevel != null && promiseStateChanged) {
                applyProgressLevel.maybePerformFinishedAnimation();
            }
        }
    }

    public void applyBadgeState(ItemInfo itemInfo, boolean animate) {
        if (mIcon instanceof FastBitmapDrawable) {
            boolean wasBadged = mBadgeInfo != null;
            mBadgeInfo = mLauncher.getPopupDataProvider().getBadgeInfoForItem(itemInfo);
            boolean isBadged = mBadgeInfo != null;
            float newBadgeScale = isBadged ? 1f : 0;
            mBadgeRenderer = mLauncher.getDeviceProfile().mBadgeRenderer;
            if (wasBadged || isBadged) {
                mBadgePalette = IconPalette.getBadgePalette(getResources());
                if (mBadgePalette == null) {
                    mBadgePalette = ((FastBitmapDrawable) mIcon).getIconPalette();
                }
                // Animate when a badge is first added or when it is removed.
                if (animate && (wasBadged ^ isBadged) && isShown()) {
                    ObjectAnimator.ofFloat(this, BADGE_SCALE_PROPERTY, newBadgeScale).start();
                } else {
                    mBadgeScale = newBadgeScale;
                    invalidate();
                }
            }
        }
    }

    public IconPalette getBadgePalette() {
        return mBadgePalette;
    }

    /**
     * Sets the icon for this view based on the layout direction.
     */
    private void setIcon(Drawable icon) {
        mIcon = icon;
        mIcon.setBounds(0, 0, mIconSize, mIconSize);
        if (mIsIconVisible) {
            applyCompoundDrawables(mIcon);
        }
    }

    private void setTextAlpha(int alpha) {
        super.setTextColor(ColorUtils.setAlphaComponent(mTextColor, alpha));
    }

    protected void applyCompoundDrawables(Drawable icon) {
        if (mLayoutHorizontal) {
            setCompoundDrawablesRelative(icon, null, null, null);
        } else {
            setCompoundDrawables(null, icon, null, null);
        }
    }

    @Override
    public void requestLayout() {
        if (!mDisableRelayout) {
            super.requestLayout();
        }
    }

    /**
     * Applies the item info if it is same as what the view is pointing to currently.
     */
    @Override
    public void reapplyItemInfo(ItemInfoWithIcon info) {
        if (getTag() == info) {
            mIconLoadRequest = null;
            mDisableRelayout = true;
            info.iconBitmap.prepareToDraw();

            if (info instanceof AppInfo) {
                applyFromApplicationInfo((AppInfo) info);
            } else if (info instanceof ShortcutInfo) {
                applyFromShortcutInfo((ShortcutInfo) info);
                if (new FolderIconPreviewVerifier(mLauncher.getDeviceProfile().inv).isItemInPreview(info.rank) && (info.container >= 0)) {
                    View folderIcon =
                            mLauncher.getWorkspace().getHomescreenIconByItemId(info.container);
                    if (folderIcon != null) {
                        folderIcon.invalidate();
                    }
                }
            } else if (info instanceof PackageItemInfo) {
                applyFromPackageItemInfo((PackageItemInfo) info);
            }

            mDisableRelayout = false;
        }
    }

    /**
     * Verifies that the current icon is high-res otherwise posts a request to load the icon.
     */
    public void verifyHighRes() {
        if (mIconLoadRequest != null) {
            mIconLoadRequest.cancel();
            mIconLoadRequest = null;
        }
        if (getTag() instanceof ItemInfoWithIcon) {
            ItemInfoWithIcon info = (ItemInfoWithIcon) getTag();
            if (info.usingLowResIcon) {
                mIconLoadRequest = LauncherAppState.getInstance(getContext()).getIconCache()
                        .updateIconInBackground(BubbleTextView.this, info);
            }
        }
    }

    /**
     * Interface to be implemented by the grand parent to allow click shadow effect.
     */
    public interface BubbleTextShadowHandler {
        void setPressedIcon(BubbleTextView icon, Bitmap background);
    }

    public int getIconSize() {
        return this.mIconSize;
    }
}
