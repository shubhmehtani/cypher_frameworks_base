package com.google.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.hardware.input.InputManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.RenderNodeAnimator;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Interpolators;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.phone.NavBarButtonProvider.ButtonInterface;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonRipple;
import com.android.systemui.statusbar.policy.KeyButtonView;

public class OpaLayout extends FrameLayout implements ButtonInterface {

    private static final int ANIMATION_STATE_NONE = 0;
    private static final int ANIMATION_STATE_DIAMOND = 1;
    private static final int ANIMATION_STATE_RETRACT = 2;
    private static final int ANIMATION_STATE_OTHER = 3;

    private static final int MIN_DIAMOND_DURATION = 100;
    private static final int COLLAPSE_ANIMATION_DURATION_RY = 83;
    private static final int COLLAPSE_ANIMATION_DURATION_BG = 100;
    private static final int LINE_ANIMATION_DURATION_Y = 275;
    private static final int LINE_ANIMATION_DURATION_X = 133;
    private static final int RETRACT_ANIMATION_DURATION = 300;
    private static final int DIAMOND_ANIMATION_DURATION = 200;
    private static final int HALO_ANIMATION_DURATION = 100;

    private static final int DOTS_RESIZE_DURATION = 200;
    private static final int HOME_RESIZE_DURATION = 83;

    private static final int HOME_REAPPEAR_ANIMATION_OFFSET = 33;
    private static final int HOME_REAPPEAR_DURATION = 150;

    private static final float DIAMOND_DOTS_SCALE_FACTOR = 0.8f;
    private static final float DIAMOND_HOME_SCALE_FACTOR = 0.625f;
    private static final float HALO_SCALE_FACTOR = 0.47619048f;

    private KeyButtonView mHome;
    private KeyButtonRipple mRipple;

    private int mAnimationState = ANIMATION_STATE_NONE;
    private final ArraySet<Animator> mCurrentAnimators = new ArraySet<Animator>();
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);

    private boolean mIsVertical;
    private boolean mIsPressed;
    private boolean mLongClicked;
    private boolean mOpaEnabled;
    private boolean mSupportsLongpress = true;
    private long mStartTime;
    private int mCode;

    private View mRed;
    private View mBlue;
    private View mGreen;
    private View mYellow;
    private View mWhite;
    private View mHalo;

    private int mDarkMode;
    private int mLightMode;

    private View mTop;
    private View mRight;
    private View mLeft;
    private View mBottom;

    private final Runnable mCheckLongPress = new Runnable() {
        @Override
        public void run() {
            if (mIsPressed) {
                // Log.d("OpaLayout", "longpressed: " + this);
                if (isLongClickable()) {
                    // Just an old-fashioned ImageView
                    performLongClick();
                    mLongClicked = true;
                } else if (mSupportsLongpress) {
                    sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    mLongClicked = true;
                }
            }
        }
    };

    private final Runnable mRetract = new Runnable() {
        @Override
        public void run() {
            cancelCurrentAnimation();
            startRetractAnimation();
        }
    };

    private final Interpolator mRetractInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
    private final Interpolator mCollapseInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
    private final Interpolator mDiamondInterpolator = new PathInterpolator(0.2f, 0f, 0.2f, 1f);
    private final Interpolator mDotsFullSizeInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
    private final Interpolator mFastOutSlowInInterpolator = Interpolators.FAST_OUT_SLOW_IN;
    private final Interpolator mHomeDisappearInterpolator = new PathInterpolator(0.8f, 0f, 1f, 1f);

    private NavBarAnimationObserver mNavBarAnimationObserver;

    protected class NavBarAnimationObserver extends ContentObserver {
        NavBarAnimationObserver(Handler handler) {
            super(handler);
        }

        void observe() {
           ContentResolver resolver = mContext.getContentResolver();
           resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.NAVIGATION_BAR_ANIMATION),
                  false, this, UserHandle.USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
           super.onChange(selfChange, uri);
           setOpaEnabled(true);
        }
    }

    public OpaLayout(Context context) {
        super(context);
        mDarkMode = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightMode = context.getColor(R.color.light_mode_icon_color_single_tone);

        if (mNavBarAnimationObserver == null) {
            mNavBarAnimationObserver = new NavBarAnimationObserver(new Handler());
        }
        mNavBarAnimationObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDarkMode = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightMode = context.getColor(R.color.light_mode_icon_color_single_tone);

        if (mNavBarAnimationObserver == null) {
            mNavBarAnimationObserver = new NavBarAnimationObserver(new Handler());
        }
        mNavBarAnimationObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDarkMode = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightMode = context.getColor(R.color.light_mode_icon_color_single_tone);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyleAttr, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, 0);

        mSupportsLongpress = a.getBoolean(R.styleable.KeyButtonView_keyRepeat, true);

        a.recycle();

        mRipple = new KeyButtonRipple(context, this);
        setBackground(mRipple);

        if (mNavBarAnimationObserver == null) {
            mNavBarAnimationObserver = new NavBarAnimationObserver(new Handler());
        }
        mNavBarAnimationObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mDarkMode = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightMode = context.getColor(R.color.light_mode_icon_color_single_tone);

        if (mNavBarAnimationObserver == null) {
            mNavBarAnimationObserver = new NavBarAnimationObserver(new Handler());
        }
        mNavBarAnimationObserver.observe();
    }

    private void startAll(ArraySet<Animator> animators) {
        for(int i=0; i < animators.size(); i++) {
            Animator curAnim = (Animator) mCurrentAnimators.valueAt(i);
            curAnim.start();
        }
    }

    private void startCollapseAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getCollapseAnimatorSet());
        mAnimationState = ANIMATION_STATE_OTHER;
        startAll(mCurrentAnimators);
    }

    private void startDiamondAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getDiamondAnimatorSet());
        mAnimationState = ANIMATION_STATE_DIAMOND;
        startAll(mCurrentAnimators);
    }

    private void startLineAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getLineAnimatorSet());
        mAnimationState = ANIMATION_STATE_OTHER;
        startAll(mCurrentAnimators);
    }

    private void startRetractAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getRetractAnimatorSet());
        mAnimationState = ANIMATION_STATE_RETRACT;
        startAll(mCurrentAnimators);
    }

    private void cancelCurrentAnimation() {
        if(mCurrentAnimators.isEmpty())
            return;
        for(int i=0; i < mCurrentAnimators.size(); i++) {
            Animator curAnim = (Animator) mCurrentAnimators.valueAt(i);
            curAnim.removeAllListeners();
            curAnim.cancel();
        }
        mCurrentAnimators.clear();
        mAnimationState = ANIMATION_STATE_NONE;
    }

    private void endCurrentAnimation() {
        if(mCurrentAnimators.isEmpty())
            return;
        for(int i=0; i < mCurrentAnimators.size(); i++) {
            Animator curAnim = (Animator) mCurrentAnimators.valueAt(i);
            curAnim.removeAllListeners();
            curAnim.end();
        }
        mCurrentAnimators.clear();
        mAnimationState = ANIMATION_STATE_NONE;
    }

    private ArraySet<Animator> getCollapseAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        Animator animator;
        if (mIsVertical) {
            animator = getDeltaAnimatorY(mRed, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_ry), COLLAPSE_ANIMATION_DURATION_RY);
        } else {
            animator = getDeltaAnimatorX(mRed, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_ry), COLLAPSE_ANIMATION_DURATION_RY);
        }
        set.add(animator);
        set.add(getScaleAnimatorX(mRed, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mRed, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        Animator animator2;
        if (mIsVertical) {
            animator2 = getDeltaAnimatorY(mBlue, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_bg), COLLAPSE_ANIMATION_DURATION_BG);
        } else {
            animator2 = getDeltaAnimatorX(mBlue, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_bg), COLLAPSE_ANIMATION_DURATION_BG);
        }
        set.add(animator2);
        set.add(getScaleAnimatorX(mBlue, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mBlue, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        Animator animator3;
        if (mIsVertical) {
            animator3 = getDeltaAnimatorY(mYellow, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_ry), COLLAPSE_ANIMATION_DURATION_RY);
        } else {
            animator3 = getDeltaAnimatorX(mYellow, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_ry), COLLAPSE_ANIMATION_DURATION_RY);
        }
        set.add(animator3);
        set.add(getScaleAnimatorX(mYellow, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mYellow, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        Animator animator4;
        if (mIsVertical) {
            animator4 = getDeltaAnimatorY(mGreen, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_bg), COLLAPSE_ANIMATION_DURATION_BG);
        } else {
            animator4 = getDeltaAnimatorX(mGreen, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_bg), COLLAPSE_ANIMATION_DURATION_BG);
        }
        set.add(animator4);
        set.add(getScaleAnimatorX(mGreen, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mGreen, 1.0f, DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        final Animator scaleAnimatorX = getScaleAnimatorX(mWhite, 1.0f, HOME_REAPPEAR_DURATION, mFastOutSlowInInterpolator);
        final Animator scaleAnimatorY = getScaleAnimatorY(mWhite, 1.0f, HOME_REAPPEAR_DURATION, mFastOutSlowInInterpolator);
        final Animator scaleAnimatorX2 = getScaleAnimatorX(mHalo, 1.0f, HOME_REAPPEAR_DURATION, mFastOutSlowInInterpolator);
        final Animator scaleAnimatorY2 = getScaleAnimatorY(mHalo, 1.0f, HOME_REAPPEAR_DURATION, mFastOutSlowInInterpolator);
        scaleAnimatorX.setStartDelay(HOME_REAPPEAR_ANIMATION_OFFSET);
        scaleAnimatorY.setStartDelay(HOME_REAPPEAR_ANIMATION_OFFSET);
        scaleAnimatorX2.setStartDelay(HOME_REAPPEAR_ANIMATION_OFFSET);
        scaleAnimatorY2.setStartDelay(HOME_REAPPEAR_ANIMATION_OFFSET);
        set.add(scaleAnimatorX);
        set.add(scaleAnimatorY);
        set.add(scaleAnimatorX2);
        set.add(scaleAnimatorY2);
        getLongestAnim((set)).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationEnd(final Animator animator) {
                mCurrentAnimators.clear();
                mAnimationState = ANIMATION_STATE_NONE;
            }
        });
        return set;
    }

    private ArraySet<Animator> getDiamondAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        set.add(getDeltaAnimatorY(mTop, mDiamondInterpolator, -getPxVal(R.dimen.opa_diamond_translation), DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mTop, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mTop, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getDeltaAnimatorY(mBottom, mDiamondInterpolator, getPxVal(R.dimen.opa_diamond_translation), DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mBottom, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mBottom, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getDeltaAnimatorX(mLeft, mDiamondInterpolator, -getPxVal(R.dimen.opa_diamond_translation), DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mLeft, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mLeft, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getDeltaAnimatorX(mRight, mDiamondInterpolator, getPxVal(R.dimen.opa_diamond_translation), DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mRight, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mRight, DIAMOND_DOTS_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorX(mWhite, DIAMOND_HOME_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mWhite, DIAMOND_HOME_SCALE_FACTOR, DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorX(mHalo, HALO_SCALE_FACTOR, MIN_DIAMOND_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mHalo, HALO_SCALE_FACTOR, MIN_DIAMOND_DURATION, mFastOutSlowInInterpolator));
        getLongestAnim(set).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationCancel(final Animator animator) {
                mCurrentAnimators.clear();
            }

            public void onAnimationEnd(final Animator animator) {
                startLineAnimation();
            }
        });
        return set;
    }

    private ArraySet<Animator> getLineAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        if (mIsVertical) {
            set.add(getDeltaAnimatorY(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_ry), LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorX(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_y_translation), LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorY(mBlue, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_bg), LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorY(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_ry), LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorX(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_y_translation), LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorY(mGreen, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_bg), LINE_ANIMATION_DURATION_Y));
        } else {
            set.add(getDeltaAnimatorX(mRed, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_ry), LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorY(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_y_translation), LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorX(mBlue, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_bg), LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorX(mYellow, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_ry), LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorY(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_y_translation), LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorX(mGreen, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_bg), LINE_ANIMATION_DURATION_Y));
        }
        set.add(getScaleAnimatorX(mWhite, 0.0f, HOME_RESIZE_DURATION, mHomeDisappearInterpolator));
        set.add(getScaleAnimatorY(mWhite, 0.0f, HOME_RESIZE_DURATION, mHomeDisappearInterpolator));
        set.add(getScaleAnimatorX(mHalo, 0.0f, HOME_RESIZE_DURATION, mHomeDisappearInterpolator));
        set.add(getScaleAnimatorY(mHalo, 0.0f, HOME_RESIZE_DURATION, mHomeDisappearInterpolator));
        getLongestAnim(set).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationCancel(final Animator animator) {
                mCurrentAnimators.clear();
            }

            public void onAnimationEnd(final Animator animator) {
                startCollapseAnimation();
            }
        });
        return set;
    }

    private ArraySet<Animator> getRetractAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        set.add(getTranslationAnimatorX(mRed, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mRed, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mRed, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mRed, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getTranslationAnimatorX(mBlue, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mBlue, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mBlue, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mBlue, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getTranslationAnimatorX(mGreen, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mGreen, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mGreen, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mGreen, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getTranslationAnimatorX(mYellow, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mYellow, mRetractInterpolator, RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mYellow, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mYellow, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorX(mWhite, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mWhite, 1.0f, RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorX(mHalo, 1.0f, RETRACT_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mHalo, 1.0f, RETRACT_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        getLongestAnim(set).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationEnd(final Animator animator) {
                mCurrentAnimators.clear();
                mAnimationState = ANIMATION_STATE_NONE;
            }
        });
        return set;
    }

    private float getPxVal(int id) {
        return getResources().getDimensionPixelOffset(id);
    }

    private Animator getDeltaAnimatorX(View v, Interpolator interpolator, float deltaX, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(8, (int) (v.getX() + deltaX));
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getDeltaAnimatorY(View v, Interpolator interpolator, float deltaY, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(9, (int) (v.getY() + deltaY));
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getScaleAnimatorX(View v, float factor, int duration, Interpolator interpolator) {
        RenderNodeAnimator anim = new RenderNodeAnimator(3, factor);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getScaleAnimatorY(View v, float factor, int duration, Interpolator interpolator) {
        RenderNodeAnimator anim = new RenderNodeAnimator(4, factor);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getTranslationAnimatorX(View v, Interpolator interpolator, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(0, 0);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getTranslationAnimatorY(View v, Interpolator interpolator, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(1, 0);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getLongestAnim(ArraySet<Animator> animators) {
        long longestDuration = -1;
        Animator longestAnim = null;

        for(int i=0; i < animators.size(); i++) {
            Animator a = (Animator) animators.valueAt(i);
            if(a.getTotalDuration() > longestDuration) {
                longestDuration = a.getTotalDuration();
                longestAnim = a;
            }
        }
        return longestAnim;
    }
  
    public void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, int flags, long when) {
        mMetricsLogger.write(new LogMaker(MetricsEvent.ACTION_NAV_BUTTON_EVENT)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(mCode)
                .addTaggedData(MetricsEvent.FIELD_NAV_ACTION, action)
                .addTaggedData(MetricsEvent.FIELD_FLAGS, flags));
        final int repeatCount = (flags & KeyEvent.FLAG_LONG_PRESS) != 0 ? 1 : 0;
        final KeyEvent ev = new KeyEvent(mStartTime, when, action, mCode, repeatCount,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_NAVIGATION_BAR);
        InputManager.getInstance().injectInputEvent(ev,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void abortCurrentGesture() {
        mHome.abortCurrentGesture();
    }

    protected void onFinishInflate() {
        super.onFinishInflate();

        mRed = findViewById(R.id.red);
        mBlue = findViewById(R.id.blue);
        mYellow = findViewById(R.id.yellow);
        mGreen = findViewById(R.id.green);
        mWhite = findViewById(R.id.white);
        mHalo = findViewById(R.id.halo);
        mHome = (KeyButtonView) findViewById(R.id.home_button);

        setOpaEnabled(true);
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mOpaEnabled) {
            return false;
        }
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                if (!mCurrentAnimators.isEmpty()) {
                    if (mAnimationState != ANIMATION_STATE_RETRACT) {
                        return false;
                    }
                    endCurrentAnimation();
                }
                mStartTime = SystemClock.elapsedRealtime();
                mLongClicked = false;
                mIsPressed = true;
                startDiamondAnimation();
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, (long)ViewConfiguration.getLongPressTimeout());
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mAnimationState == ANIMATION_STATE_DIAMOND) {
                    final long elapsedRealtime = SystemClock.elapsedRealtime();
                    removeCallbacks(mRetract);
                    postDelayed(mRetract, 100L - (elapsedRealtime - mStartTime));
                    removeCallbacks(mCheckLongPress);
                    return false;
                }
                int n;
                if (!mIsPressed || mLongClicked) {
                    n = 0;
                } else {
                    n = 1;
                }
                mIsPressed = false;
                if (n != 0) {
                    mRetract.run();
                    return false;
                }
                break;
            }
        }
        return false;
    }

    public void setCarMode(boolean carMode) {
        setOpaEnabled(!carMode);
    }

    public void setImageDrawable(Drawable drawable) {
        ((ImageView) mWhite).setImageDrawable(drawable);
    }

    public void setImageResource(int resId) {
        ((ImageView) mWhite).setImageResource(resId);
    }

    public void setDarkIntensity(float darkIntensity) {
        int backgroundColor = getBackgroundColor(darkIntensity);
        ((ImageView) mWhite).setColorFilter(new PorterDuffColorFilter(backgroundColor, PorterDuff.Mode.SRC_ATOP));
        ((ImageView) mHalo).setColorFilter(new PorterDuffColorFilter(backgroundColor, PorterDuff.Mode.SRC_ATOP));
        invalidate();
    }

    private int getBackgroundColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightMode, mDarkMode);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightMode, int darkMode) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightMode, darkMode);
    }

    public void setVertical(boolean vertical) {
        mIsVertical = vertical;
        if (mIsVertical) {
            mTop = mGreen;
            mBottom = mBlue;
            mRight = mYellow;
            mLeft = mRed;
            return;
        }
        mTop = mRed;
        mBottom = mYellow;
        mLeft = mBlue;
        mRight = mGreen;
    }

    @Override
    public boolean isClickable() {
        return mCode != 0 || super.isClickable();
    }

    public void setCode(int code) {
        mCode = code;
    }

    public void setOnTouchListener(View.OnTouchListener l) {
        mHome.setOnTouchListener(l);
    }

    public void setOpaEnabled(boolean enabled) {
        final boolean isEnabled = Settings.System.getIntForUser(this.getContext().getContentResolver(),
            Settings.System.NAVIGATION_BAR_ANIMATION, 1, UserHandle.USER_CURRENT) == 1;
        final boolean configValue = getContext().getResources().getBoolean(com.android.internal.R.bool.config_allowOpaLayout);
        final boolean shouldEnable = configValue && (enabled || UserManager.isDeviceInDemoMode(getContext())) && isEnabled;
        mOpaEnabled = shouldEnable;

        int visibility = shouldEnable ? View.VISIBLE : View.INVISIBLE;

        mBlue.setVisibility(visibility);
        mRed.setVisibility(visibility);
        mYellow.setVisibility(visibility);
        mGreen.setVisibility(visibility);
    }

}
