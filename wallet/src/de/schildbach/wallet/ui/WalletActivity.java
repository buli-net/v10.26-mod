/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import com.google.common.primitives.Floats;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.R;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.backup.BackupWalletActivity;
import de.schildbach.wallet.ui.backup.RestoreWalletDialogFragment;
import de.schildbach.wallet.ui.monitor.NetworkMonitorActivity;
import de.schildbach.wallet.ui.preference.PreferenceActivity;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet.util.OnFirstPreDraw;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.script.Script;
// add bar sync
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;
//end
/**
 * @author Andreas Schildbach
 */
public final class WalletActivity extends AbstractWalletActivity {
    private WalletApplication application;
    private Configuration config;

    private Handler handler = new Handler();

    private AnimatorSet enterAnimation;
    private View contentView;
    private View exchangeRatesFragment;
    private View levitateView;

    private AbstractWalletActivityViewModel walletActivityViewModel;
    private WalletActivityViewModel viewModel;

    private final ActivityResultLauncher<Void> scanLauncher =
            registerForActivityResult(new ScanActivity.Scan(), input -> {
                if (input == null) return;
                new StringInputParser(input) {
                    @Override
                    protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                        SendCoinsActivity.start(WalletActivity.this, paymentIntent);
                    }

                    @Override
                    protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
                        if (Constants.ENABLE_SWEEP_WALLET)
                            SweepWalletActivity.start(WalletActivity.this, key);
                        else
                            super.handlePrivateKey(key);
                    }

                    @Override
                    protected void handleDirectTransaction(final Transaction tx) throws VerificationException {
                        walletActivityViewModel.broadcastTransaction(tx);
                    }

                    @Override
                    protected void error(final int messageResId, final Object... messageArgs) {
                        final DialogBuilder dialog = DialogBuilder.dialog(WalletActivity.this, R.string.button_scan,
                                messageResId, messageArgs);
                        dialog.singleDismissButton(null);
                        dialog.show();
                    }
                }.parse();
            });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        EdgeToEdge.enable(this, SystemBarStyle.dark(getColor(R.color.bg_action_bar)),
                SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        this.application = getWalletApplication();
        this.config = application.getConfiguration();

        walletActivityViewModel = new ViewModelProvider(this).get(AbstractWalletActivityViewModel.class);
        viewModel = new ViewModelProvider(this).get(WalletActivityViewModel.class);

        setContentView(R.layout.wallet_content);
        setActionBar(findViewById(R.id.wallet_appbar));
        getActionBar().setDisplayHomeAsUpEnabled(false);
        contentView = findViewById(android.R.id.content);


        //add sync bar
        
final View root = findViewById(android.R.id.content);
final SharedPreferences prefs = getSharedPreferences("sync_prefs", MODE_PRIVATE);
final int[] lastProg = { -1 };
final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
bar.setMax(10000);
bar.setVisibility(View.GONE);
((ViewGroup) getWindow().getDecorView()).addView(bar,
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (3 * getResources().getDisplayMetrics().density)));
final TextView percent = new TextView(this);
percent.setTextSize(12);
percent.setVisibility(View.GONE);
((ViewGroup) getWindow().getDecorView()).addView(percent);

root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
    @Override
    public void onGlobalLayout() {
        TextView tv = findSync((ViewGroup) root);
        if (tv == null || tv.getVisibility()!= View.VISIBLE) {
            bar.setVisibility(View.GONE);
            percent.setVisibility(View.GONE);
            return;
        }

                
// lấy màu động
int syncTextColor = tv.getCurrentTextColor();
percent.setTextColor(syncTextColor);

// thay đoạn tìm "Bitcoin" bằng:
int btcColor = syncTextColor;   // <-- khai báo luôn ở đây
if (tv != null) {
    btcColor = tv.getCurrentTextColor(); // thực ra = syncTextColor
}

// --- LẤY MÀU BTC TỪ TIÊU ĐỀ "Bitcoin" ---
/*int btcColor = syncTextColor; // fallback
ViewGroup decor = (ViewGroup) getWindow().getDecorView();
TextView title = findTextViewWithText(decor, "Bitcoin");
if (title != null) {
    btcColor = title.getCurrentTextColor();
}*/
bar.setProgressTintList(android.content.res.ColorStateList.valueOf(btcColor));
bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(btcColor & 0x33FFFFFF));
//end
        int[] loc = new int[2];
        tv.getLocationOnScreen(loc);
        int left = loc[0];
        int top = loc[1];
        float d = getResources().getDisplayMetrics().density;

        percent.setText(String.format(Locale.US, "%.2f%%", lastProg[0] / 100f));
        percent.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int percentW = percent.getMeasuredWidth();
        int gap = (int)(8 * d);
        int padEnd = (int)(8 * d);
        int textW = (int) tv.getPaint().measureText(tv.getText().toString());
        int wantedWidth = textW + gap + percentW + padEnd;

        View qr = findQr((ViewGroup) root);
        int qrLeft = qr!= null? getLeftOnScreen(qr) : root.getWidth();
        int maxAllowed = Math.max(0, qrLeft - left - (int)(8 * d));
        int barWidth = Math.min(wantedWidth, maxAllowed);

        int percentX = left + textW + gap;
        if (percentX + percentW > left + barWidth - padEnd) {
            percentX = left + barWidth - padEnd - percentW;
        }
        percentX = Math.max(percentX, left + (int)(4 * d));

        percent.setX(percentX);
        // canh % theo baseline của chữ sync
        int tvBaseline = tv.getBaseline();
        int pBaseline = percent.getBaseline();
        if (tvBaseline > 0 && pBaseline > 0) {
        percent.setY(top + tvBaseline - pBaseline);
        } else {
      // fallback nếu baseline chưa có
        percent.setY(top + tv.getHeight() - percent.getMeasuredHeight());
        }
        //end
        percent.setVisibility(View.VISIBLE);
        bar.setX(left);
        bar.setY(top + tv.getHeight() + (int)(4 * d));
        bar.getLayoutParams().width = barWidth;
        bar.setVisibility(View.VISIBLE);

        String s = tv.getText().toString().toLowerCase();
        int h = 0;
        try {
            int v = Integer.parseInt(s.replaceAll("[^0-9]", ""));
            if (s.contains("hour")) h = v;
            else if (s.contains("day")) h = v * 24;
            else if (s.contains("week")) h = v * 7 * 24;
            else if (s.contains("month")) h = v * 30 * 24;
            else if (s.contains("year")) h = v * 365 * 24;
        } catch (Exception ignored) {}
        int max = prefs.getInt("max_hours", 0);
        if (h > max) { max = h; prefs.edit().putInt("max_hours", max).apply(); }
        if (h == 0 && max!= 0) { prefs.edit().remove("max_hours").apply(); max = 0; }
        int prog = max > 0? (int)((max - h) * 10000L / max) : 0;
        if (prog!= lastProg[0]) {
            lastProg[0] = prog;
            percent.setText(String.format(Locale.US, "%.2f%%", prog / 100f));
            bar.setProgress(prog);
        }
    }

    // tìm chữ bitcoin trên màn hình, lấy màu mẫu để tô màu cho thanh bar sync
    private TextView findSync(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && ((TextView) v).getText().toString().contains("Synchronizing"))
                return (TextView) v;
            if (v instanceof ViewGroup) {
                TextView t = findSync((ViewGroup) v);
                if (t!= null) return t;
            }
        }
        return null;
    }

//end//
private TextView findTextViewWithText(ViewGroup g, String txt) {
    for (int i = 0; i < g.getChildCount(); i++) {
        View v = g.getChildAt(i);
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(txt))
            return (TextView) v;
        if (v instanceof ViewGroup) {
            TextView t = findTextViewWithText((ViewGroup) v, txt);
            if (t != null) return t;
        }
    }
    return null;
}
    
    private View findQr(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof ImageView && v.getWidth() > 50 && v.getX() > g.getWidth() * 0.6)
                return v;
            if (v instanceof ViewGroup) {
                View t = findQr((ViewGroup) v);
                if (t!= null) return t;
            }
        }
        return null;
    }
    private int getLeftOnScreen(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return l[0];
    }
});
        //end add sync bar
        
        final View insetTopView = contentView.findViewWithTag("inset_top");
        if (insetTopView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(insetTopView, (v, windowInsets) -> {
                final Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), v.getPaddingBottom());
                return windowInsets;
            });
        }
        final View insetBottomView = contentView.findViewWithTag("inset_bottom");
        if (insetBottomView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(insetBottomView, (v, windowInsets) -> {
                final Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                if (insets.bottom > 0 && v instanceof LinearLayout) {
                    final LinearLayout layout = (LinearLayout) v;
                    layout.setShowDividers(layout.getShowDividers() | LinearLayout.SHOW_DIVIDER_END);
                }
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
                return windowInsets;
            });
        }

        exchangeRatesFragment = findViewById(R.id.wallet_main_twopanes_exchange_rates);
        levitateView = contentView.findViewWithTag("levitate");

        // Make view tagged with 'levitate' scroll away and quickly return.
        if (levitateView != null) {
            final CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(
                    levitateView.getLayoutParams().width, levitateView.getLayoutParams().height);
            layoutParams.setBehavior(new QuickReturnBehavior());
            levitateView.setLayoutParams(layoutParams);
            levitateView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                final int height = bottom - top;
                final View targetList = findViewById(R.id.wallet_transactions_list);
                targetList.setPadding(targetList.getPaddingLeft(), height, targetList.getPaddingRight(),
                        targetList.getPaddingBottom());
                final View targetEmpty = findViewById(R.id.wallet_transactions_empty);
                targetEmpty.setPadding(targetEmpty.getPaddingLeft(), height, targetEmpty.getPaddingRight(),
                        targetEmpty.getPaddingBottom());
            });
        }

        OnFirstPreDraw.listen(contentView, viewModel);
        enterAnimation = buildEnterAnimation(contentView);

        viewModel.walletEncrypted.observe(this, isEncrypted -> invalidateOptionsMenu());
        viewModel.walletLegacyFallback.observe(this, isLegacyFallback -> invalidateOptionsMenu());
        viewModel.showHelpDialog.observe(this, new Event.Observer<Integer>() {
            @Override
            protected void onEvent(final Integer messageResId) {
                HelpDialogFragment.page(getSupportFragmentManager(), messageResId);
            }
        });
        viewModel.showBackupWalletDialog.observe(this, new Event.Observer<Void>() {
            @Override
            protected void onEvent(final Void v) {
                BackupWalletActivity.start(WalletActivity.this);
            }
        });
        viewModel.showRestoreWalletDialog.observe(this, new Event.Observer<Void>() {
            @Override
            protected void onEvent(final Void v) {
                RestoreWalletDialogFragment.showPick(getSupportFragmentManager());
            }
        });
        viewModel.showEncryptKeysDialog.observe(this, new Event.Observer<Void>() {
            @Override
            protected void onEvent(final Void v) {
                EncryptKeysDialogFragment.show(getSupportFragmentManager());
            }
        });
        viewModel.showReportIssueDialog.observe(this, new Event.Observer<Void>() {
            @Override
            protected void onEvent(final Void v) {
                ReportIssueDialogFragment.show(getSupportFragmentManager(), R.string.report_issue_dialog_title_issue,
                        R.string.report_issue_dialog_message_issue, Constants.REPORT_SUBJECT_ISSUE, null);
            }
        });
        viewModel.showReportCrashDialog.observe(this, new Event.Observer<Void>() {
            @Override
            protected void onEvent(final Void v) {
                ReportIssueDialogFragment.show(getSupportFragmentManager(), R.string.report_issue_dialog_title_crash,
                        R.string.report_issue_dialog_message_crash, Constants.REPORT_SUBJECT_CRASH, null);
            }
        });
        viewModel.enterAnimation.observe(this, state -> {
            if (state == WalletActivityViewModel.EnterAnimationState.WAITING) {
                enterAnimation.setCurrentPlayTime(0);
            } else if (state == WalletActivityViewModel.EnterAnimationState.ANIMATING) {
                reportFullyDrawn();
                enterAnimation.start();
                enterAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(final Animator animation) {
                        viewModel.animationFinished();
                    }
                });
            } else if (state == WalletActivityViewModel.EnterAnimationState.FINISHED) {
                getWindow().getDecorView().setBackground(null);
            }
        });
        if (savedInstanceState == null)
            viewModel.animateWhenLoadingFinished();
        else
            viewModel.animationFinished();

        if (savedInstanceState == null && CrashReporter.hasSavedCrashTrace())
            viewModel.showReportCrashDialog.setValue(Event.simple());

        config.touchLastUsed();

        handleIntent(getIntent());

        addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(final Menu menu, final MenuInflater inflater) {
                inflater.inflate(R.menu.wallet_options, menu);
            }

            @Override
            public void onPrepareMenu(final Menu menu) {
                final Resources res = getResources();
                final boolean showExchangeRatesOption = config.isEnableExchangeRates()
                        && res.getBoolean(R.bool.show_exchange_rates_option);
                menu.findItem(R.id.wallet_options_exchange_rates).setVisible(showExchangeRatesOption);
                menu.findItem(R.id.wallet_options_sweep_wallet).setVisible(Constants.ENABLE_SWEEP_WALLET);
                final String externalStorageState = Environment.getExternalStorageState();
                final boolean enableRestoreWalletOption = Environment.MEDIA_MOUNTED.equals(externalStorageState)
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(externalStorageState);
                menu.findItem(R.id.wallet_options_restore_wallet).setEnabled(enableRestoreWalletOption);
                final Boolean isEncrypted = viewModel.walletEncrypted.getValue();
                if (isEncrypted != null) {
                    final MenuItem encryptKeysOption = menu.findItem(R.id.wallet_options_encrypt_keys);
                    encryptKeysOption.setTitle(isEncrypted ? R.string.wallet_options_encrypt_keys_change
                            : R.string.wallet_options_encrypt_keys_set);
                    encryptKeysOption.setVisible(true);
                }
                final Boolean isLegacyFallback = viewModel.walletLegacyFallback.getValue();
                if (isLegacyFallback != null) {
                    final MenuItem requestLegacyOption = menu.findItem(R.id.wallet_options_request_legacy);
                    requestLegacyOption.setVisible(isLegacyFallback);
                }
            }

            @Override
            public boolean onMenuItemSelected(final MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.wallet_options_request) {
                    handleRequestCoins();
                    return true;
                } else if (itemId == R.id.wallet_options_request_legacy) {
                    RequestCoinsActivity.start(WalletActivity.this, Script.ScriptType.P2PKH);
                    return true;
                } else if (itemId == R.id.wallet_options_send) {
                    handleSendCoins();
                    return true;
                } else if (itemId == R.id.wallet_options_scan) {
                    handleScan(null);
                    return true;
                } else if (itemId == R.id.wallet_options_address_book) {
                    AddressBookActivity.start(WalletActivity.this);
                    return true;
                } else if (itemId == R.id.wallet_options_exchange_rates) {
                    startActivity(new Intent(WalletActivity.this, ExchangeRatesActivity.class));
                    return true;
                } else if (itemId == R.id.wallet_options_sweep_wallet) {
                    SweepWalletActivity.start(WalletActivity.this);
                    return true;
                } else if (itemId == R.id.wallet_options_network_monitor) {
                    startActivity(new Intent(WalletActivity.this, NetworkMonitorActivity.class));
                    return true;
                } else if (itemId == R.id.wallet_options_restore_wallet) {
                    viewModel.showRestoreWalletDialog.setValue(Event.simple());
                    return true;
                } else if (itemId == R.id.wallet_options_backup_wallet) {
                    viewModel.showBackupWalletDialog.setValue(Event.simple());
                    return true;
                } else if (itemId == R.id.wallet_options_encrypt_keys) {
                    viewModel.showEncryptKeysDialog.setValue(Event.simple());
                    return true;
                } else if (itemId == R.id.wallet_options_preferences) {
                    startActivity(new Intent(WalletActivity.this, PreferenceActivity.class));
                    return true;
                } else if (itemId == R.id.wallet_options_safety) {
                    viewModel.showHelpDialog.setValue(new Event<>(R.string.help_safety));
                    return true;
                } else if (itemId == R.id.wallet_options_technical_notes) {
                    viewModel.showHelpDialog.setValue(new Event<>(R.string.help_technical_notes));
                    return true;
                } else if (itemId == R.id.wallet_options_report_issue) {
                    viewModel.showReportIssueDialog.setValue(Event.simple());
                    return true;
                } else if (itemId == R.id.wallet_options_help) {
                    viewModel.showHelpDialog.setValue(new Event<>(R.string.help_wallet));
                    return true;
                }
                return false;
            }
        });

        final FragmentManager fragmentManager = getSupportFragmentManager();
        MaybeMaintenanceFragment.add(fragmentManager);
        AlertDialogsFragment.add(fragmentManager);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (exchangeRatesFragment != null)
            exchangeRatesFragment.setVisibility(config.isEnableExchangeRates() ? View.VISIBLE : View.GONE);

        handler.postDelayed(() -> {
            // delayed start so that UI has enough time to initialize
            BlockchainService.start(WalletActivity.this, true);
        }, 1000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    private AnimatorSet buildEnterAnimation(final View contentView) {
        final Drawable background = getWindow().getDecorView().getBackground();
        final int duration = getResources().getInteger(android.R.integer.config_mediumAnimTime);
        final Animator splashFadeOut = AnimatorInflater.loadAnimator(WalletActivity.this, R.animator.fade_out_drawable);
        splashFadeOut.setTarget(((LayerDrawable) background).getDrawable(1));
        final AnimatorSet fragmentEnterAnimation = new AnimatorSet();
        final AnimatorSet.Builder fragmentEnterAnimationBuilder = fragmentEnterAnimation.play(splashFadeOut);

        final View slideInLeftView = contentView.findViewWithTag("slide_in_left");
        if (slideInLeftView != null) {
            final ValueAnimator slide = ValueAnimator.ofFloat(-1.0f, 0.0f);
            slide.addUpdateListener(animator -> {
                float animatedValue = (float) animator.getAnimatedValue();
                slideInLeftView.setTranslationX(
                        animatedValue * (slideInLeftView.getWidth() + slideInLeftView.getPaddingLeft()));
            });
            slide.setInterpolator(new DecelerateInterpolator());
            slide.setDuration(duration);
            slide.setTarget(slideInLeftView);
            final Animator fadeIn = AnimatorInflater.loadAnimator(WalletActivity.this, R.animator.fade_in_view);
            fadeIn.setTarget(slideInLeftView);
            fragmentEnterAnimationBuilder.before(slide).before(fadeIn);
        }

        final View slideInRightView = contentView.findViewWithTag("slide_in_right");
        if (slideInRightView != null) {
            final ValueAnimator slide = ValueAnimator.ofFloat(1.0f, 0.0f);
            slide.addUpdateListener(animator -> {
                float animatedValue = (float) animator.getAnimatedValue();
                slideInRightView.setTranslationX(
                        animatedValue * (slideInRightView.getWidth() + slideInRightView.getPaddingRight()));
            });
            slide.setInterpolator(new DecelerateInterpolator());
            slide.setDuration(duration);
            slide.setTarget(slideInRightView);
            final Animator fadeIn = AnimatorInflater.loadAnimator(WalletActivity.this, R.animator.fade_in_view);
            fadeIn.setTarget(slideInRightView);
            fragmentEnterAnimationBuilder.before(slide).before(fadeIn);
        }

        final View slideInTopView = contentView.findViewWithTag("slide_in_top");
        if (slideInTopView != null) {
            final ValueAnimator slide = ValueAnimator.ofFloat(-1.0f, 0.0f);
            slide.addUpdateListener(animator -> {
                float animatedValue = (float) animator.getAnimatedValue();
                slideInTopView.setTranslationY(
                        animatedValue * (slideInTopView.getHeight() + slideInTopView.getPaddingTop()));
            });
            slide.setInterpolator(new DecelerateInterpolator());
            slide.setDuration(duration);
            slide.setTarget(slideInTopView);
            final Animator fadeIn = AnimatorInflater.loadAnimator(WalletActivity.this, R.animator.fade_in_view);
            fadeIn.setTarget(slideInTopView);
            fragmentEnterAnimationBuilder.before(slide).before(fadeIn);
        }

        final View slideInBottomView = contentView.findViewWithTag("slide_in_bottom");
        if (slideInBottomView != null) {
            final ValueAnimator slide = ValueAnimator.ofFloat(1.0f, 0.0f);
            slide.addUpdateListener(animator -> {
                float animatedValue = (float) animator.getAnimatedValue();
                slideInBottomView.setTranslationY(
                        animatedValue * (slideInBottomView.getHeight() + slideInBottomView.getPaddingBottom()));
            });
            slide.setInterpolator(new DecelerateInterpolator());
            slide.setDuration(duration);
            slide.setTarget(slideInBottomView);
            final Animator fadeIn = AnimatorInflater.loadAnimator(WalletActivity.this, R.animator.fade_in_view);
            fadeIn.setTarget(slideInBottomView);
            fragmentEnterAnimationBuilder.before(slide).before(fadeIn);
        }

        if (levitateView != null) {
            final ObjectAnimator elevate = ObjectAnimator.ofFloat(levitateView, "elevation", 0.0f,
                    levitateView.getElevation());
            elevate.setDuration(duration);
            fragmentEnterAnimationBuilder.before(elevate);
            final Drawable levitateBackground = levitateView.getBackground();
            final Animator fadeIn = AnimatorInflater.loadAnimator(WalletActivity.this, R.animator.fade_in_drawable);
            fadeIn.setTarget(levitateBackground);
            fragmentEnterAnimationBuilder.before(fadeIn);
        }

        return fragmentEnterAnimation;
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        final String action = intent.getAction();

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            final String inputType = intent.getType();
            final NdefMessage ndefMessage = (NdefMessage) intent
                    .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
            final byte[] input = Nfc.extractMimePayload(Constants.MIMETYPE_TRANSACTION, ndefMessage);

            new BinaryInputParser(inputType, input) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    cannotClassify(inputType);
                }

                @Override
                protected void error(final int messageResId, final Object... messageArgs) {
                    final DialogBuilder dialog = DialogBuilder.dialog(WalletActivity.this, 0, messageResId, messageArgs);
                    dialog.singleDismissButton(null);
                    dialog.show();
                }
            }.parse();
        }
    }

    public void handleRequestCoins() {
        RequestCoinsActivity.start(this);
    }

    public void handleSendCoins() {
        startActivity(new Intent(this, SendCoinsActivity.class));
    }

    public void handleScan(final View clickView) {
        // The animation must be ended because of several graphical glitching that happens when the
        // Camera/SurfaceView is used while the animation is running.
        enterAnimation.end();
        if (clickView != null) {
            final ActivityOptionsCompat options = ActivityOptionsCompat.makeClipRevealAnimation(clickView, 0, 0,
                    clickView.getWidth(), clickView.getHeight());
            scanLauncher.launch(null, options);
        } else {
            scanLauncher.launch(null);
        }
    }

    private static final class QuickReturnBehavior extends CoordinatorLayout.Behavior<View> {
        @Override
        public boolean onStartNestedScroll(final CoordinatorLayout coordinatorLayout, final View child,
                final View directTargetChild, final View target, final int nestedScrollAxes, final int type) {
            return (nestedScrollAxes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        }

        @Override
        public void onNestedScroll(final CoordinatorLayout coordinatorLayout, final View child, final View target,
                final int dxConsumed, final int dyConsumed, final int dxUnconsumed, final int dyUnconsumed,
                final int type) {
            child.setTranslationY(Floats.constrainToRange(child.getTranslationY() - dyConsumed, -child.getHeight(), 0));
        }
    }
}
