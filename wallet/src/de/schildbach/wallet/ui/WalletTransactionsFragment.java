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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ViewAnimator;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.R;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.addressbook.AddressBookDao;
import de.schildbach.wallet.addressbook.AddressBookDatabase;
import de.schildbach.wallet.ui.TransactionsAdapter.WarningType;
import de.schildbach.wallet.ui.send.RaiseFeeDialogFragment;
import de.schildbach.wallet.util.Qr;
import de.schildbach.wallet.util.WalletUtils;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andreas Schildbach
 */
public class WalletTransactionsFragment extends Fragment implements TransactionsAdapter.OnClickListener,
        TransactionsAdapter.ContextMenuCallback {
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private FragmentManager fragmentManager;
    private AddressBookDao addressBookDao;
    private DevicePolicyManager devicePolicyManager;

    private ViewAnimator viewGroup;
    private TextView emptyView;
    private RecyclerView recyclerView;
    private TransactionsAdapter adapter;
    private MenuItem filterMenuItem;

    private WalletActivityViewModel activityViewModel;
    private WalletTransactionsViewModel viewModel;

    private static final int SHOW_QR_THRESHOLD_BYTES = 2500;

    private static final Logger log = LoggerFactory.getLogger(WalletTransactionsFragment.class);

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        this.activity = (AbstractWalletActivity) context;
        this.application = activity.getWalletApplication();
        this.config = application.getConfiguration();
        this.addressBookDao = AddressBookDatabase.getDatabase(context).addressBookDao();
        this.devicePolicyManager = application.getSystemService(DevicePolicyManager.class);
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.fragmentManager = getChildFragmentManager();

        activityViewModel = new ViewModelProvider(activity).get(WalletActivityViewModel.class);
        viewModel = new ViewModelProvider(this).get(WalletTransactionsViewModel.class);

        viewModel.direction.observe(this, direction -> activity.invalidateOptionsMenu());
        viewModel.transactions.observe(this, transactions -> {
            if (transactions.isEmpty()) {
                viewGroup.setDisplayedChild(0);

                final WalletTransactionsViewModel.Direction direction = viewModel.direction.getValue();
                final WarningType warning = viewModel.warning.getValue();
                final SpannableStringBuilder emptyText = new SpannableStringBuilder(
                        getString(direction == WalletTransactionsViewModel.Direction.SENT
                                ? R.string.wallet_transactions_fragment_empty_text_sent
                                : R.string.wallet_transactions_fragment_empty_text_received));
                emptyText.setSpan(new StyleSpan(Typeface.BOLD), 0, emptyText.length(),
                        SpannableStringBuilder.SPAN_POINT_MARK);
                if (direction != WalletTransactionsViewModel.Direction.SENT)
                    emptyText.append("\n\n")
                            .append(getString(R.string.wallet_transactions_fragment_empty_text_howto));
                if (warning == WarningType.BACKUP) {
                    final int start = emptyText.length();
                    emptyText.append("\n\n")
                            .append(getString(R.string.wallet_transactions_fragment_empty_remind_backup));
                    emptyText.setSpan(new StyleSpan(Typeface.BOLD), start, emptyText.length(),
                            SpannableStringBuilder.SPAN_POINT_MARK);
                }
                emptyView.setText(emptyText);
            } else {
                viewGroup.setDisplayedChild(1);
            }
        });
        viewModel.selectedTransaction.observe(this, transactionId -> {
            adapter.setSelectedTransaction(transactionId);
            final int position = adapter.positionOf(transactionId);
            if (position != RecyclerView.NO_POSITION)
                recyclerView.smoothScrollToPosition(position);
        });
        viewModel.list.observe(this, listItems -> {
            adapter.submitList(listItems);
            activityViewModel.transactionsLoadingFinished();
        });
        viewModel.showBitmapDialog.observe(this, new Event.Observer<Bitmap>() {
            @Override
            protected void onEvent(final Bitmap bitmap) {
                BitmapFragment.show(fragmentManager, bitmap);
            }
        });
        viewModel.showEditAddressBookEntryDialog.observe(this, new Event.Observer<Address>() {
            @Override
            protected void onEvent(final Address address) {
                EditAddressBookEntryFragment.edit(fragmentManager, address);
            }
        });
        viewModel.showReportIssueDialog.observe(this, new Event.Observer<Sha256Hash>() {
            @Override
            protected void onEvent(final Sha256Hash transactionHash) {
                ReportIssueDialogFragment.show(fragmentManager, R.string.report_issue_dialog_title_transaction,
                        R.string.report_issue_dialog_message_issue, Constants.REPORT_SUBJECT_ISSUE, transactionHash);
            }
        });

        adapter = new TransactionsAdapter(activity, this, this);

        activity.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(final Menu menu, final MenuInflater inflater) {
                inflater.inflate(R.menu.wallet_transactions_fragment_options, menu);
                filterMenuItem = menu.findItem(R.id.wallet_transactions_options_filter);
            }

            @Override
            public void onPrepareMenu(final Menu menu) {
                final WalletTransactionsViewModel.Direction direction = viewModel.direction.getValue();
                if (direction == null) {
                    menu.findItem(R.id.wallet_transactions_options_filter_all).setChecked(true);
                    filterMenuItem.setIcon(R.drawable.ic_filter_list_white_24dp);
                } else if (direction == WalletTransactionsViewModel.Direction.RECEIVED) {
                    menu.findItem(R.id.wallet_transactions_options_filter_received).setChecked(true);
                    filterMenuItem.setIcon(R.drawable.transactions_list_filter_received);
                } else if (direction == WalletTransactionsViewModel.Direction.SENT) {
                    menu.findItem(R.id.wallet_transactions_options_filter_sent).setChecked(true);
                    filterMenuItem.setIcon(R.drawable.transactions_list_filter_sent);
                }
            }

            @Override
            public boolean onMenuItemSelected(final MenuItem item) {
                final int itemId = item.getItemId();
                if (itemId == R.id.wallet_transactions_options_filter_all) {
                    viewModel.setDirection(null);
                    filterMenuItem.setIcon(R.drawable.ic_filter_list_white_24dp);
                    return true;
                } else if (itemId == R.id.wallet_transactions_options_filter_received) {
                    viewModel.setDirection(WalletTransactionsViewModel.Direction.RECEIVED);
                    filterMenuItem.setIcon(R.drawable.transactions_list_filter_received);
                    return true;
                } else if (itemId == R.id.wallet_transactions_options_filter_sent) {
                    viewModel.setDirection(WalletTransactionsViewModel.Direction.SENT);
                    filterMenuItem.setIcon(R.drawable.transactions_list_filter_sent);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.wallet_transactions_fragment, container, false);

        viewGroup = view.findViewById(R.id.wallet_transactions_group);

        emptyView = view.findViewById(R.id.wallet_transactions_empty);

        recyclerView = view.findViewById(R.id.wallet_transactions_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new StickToTopLinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int PADDING = 2
                    * activity.getResources().getDimensionPixelOffset(R.dimen.card_margin_vertical);

            @Override
            public void getItemOffsets(final Rect outRect, final View view, final RecyclerView parent,
                    final RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                final int position = parent.getChildAdapterPosition(view);
                if (position == 0)
                    outRect.top += PADDING;
                else if (position == parent.getAdapter().getItemCount() - 1)
                    outRect.bottom += PADDING;
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            final boolean hasBottomBar = !getResources().getBoolean(R.bool.wallet_actions_top);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), hasBottomBar ?
                    v.getPaddingBottom() : insets.bottom);
            return windowInsets;
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.setWarning(warning());
    }

    @Override
    public void onTransactionClick(final View view, final Sha256Hash transactionId) {
        viewModel.selectedTransaction.setValue(transactionId);
    }

        //add show transaction


public void showTransactionDetails(final Sha256Hash transactionId) {
    viewModel.selectedTransaction.setValue(transactionId);
    final Wallet wallet = viewModel.wallet.getValue();
    final Transaction tx = wallet.getTransaction(transactionId);
    if (tx == null) {
        return;
    }

    final android.app.Activity activity = getActivity();
    if (activity == null) return;

    boolean txSent = false;
    try {
        txSent = wallet != null && tx.getValue(wallet).signum() < 0;
    } catch (Exception e) {
    }

    org.bitcoinj.core.Coin _netValue = null;
    try {
        _netValue = tx.getValue(wallet);
    } catch (Exception e) {
    }
    final org.bitcoinj.core.Coin netValue = _netValue;

    int confs = 0;
    try {
        confs = tx.getConfidence().getDepthInBlocks();
    } catch (Exception e) {
    }

    int height = 0;
    try {
        height = tx.getConfidence().getAppearedAtChainHeight();
    } catch (Exception e) {
    }

    org.bitcoinj.core.Coin _fee = null;
    try {
        _fee = tx.getFee();
    } catch (Exception e) {
    }
    final org.bitcoinj.core.Coin fee = _fee;

    boolean rbf = false;
    try {
        rbf = tx.isOptInFullRBF();
    } catch (Exception e) {
    }

    java.util.Date updateTime = null;
    try {
        updateTime = tx.getUpdateTime();
    } catch (Exception e) {
    }

    String timeStr = updateTime != null ? new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(updateTime) : "n/a";
    int vsize = (tx.getWeight() + 3) / 4;
    boolean isCoinbase = tx.isCoinBase();
    long version = tx.getVersion();
    long locktime = tx.getLockTime();
    boolean isSegWit = tx.hasWitnesses();
    String status = confs <= 0 ? "PENDING" : confs < 6 ? "BUILDING" : "CONFIRMED";

    org.bitcoinj.core.Coin totalFrom = org.bitcoinj.core.Coin.ZERO;
    java.util.List<String> fromLines = new java.util.ArrayList<String>();
    java.util.List<String> inDetails = new java.util.ArrayList<String>();

    for (int i = 0; i < tx.getInputs().size(); i++) {
        org.bitcoinj.core.TransactionInput in = tx.getInputs().get(i);
        try {
            org.bitcoinj.core.TransactionOutput c = in.getConnectedOutput();
            org.bitcoinj.core.Coin v = c != null ? c.getValue() : null;
            if (v != null) {
                totalFrom = totalFrom.add(v);
            }
            String addr = "unknown";
            String typeIn = "nonstandard";
            try {
                try {
                    addr = c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS).toString();
                } catch (Exception e1) {
                    addr = c.getScriptPubKey().getToAddress(org.bitcoinj.params.TestNet3Params.get()).toString();
                }
                if (addr.startsWith("bc1q") || addr.startsWith("tb1q")) typeIn = "P2WPKH";
                else if (addr.startsWith("bc1p") || addr.startsWith("tb1p")) typeIn = "P2TR";
                else if (addr.startsWith("bc1") || addr.startsWith("tb1")) typeIn = "P2WSH";
                else if (addr.startsWith("3") || addr.startsWith("2")) typeIn = "P2SH";
                else if (addr.startsWith("1") || addr.startsWith("m") || addr.startsWith("n")) typeIn = "P2PKH";
            } catch (Exception e) {
            }
            fromLines.add(addr + " (" + typeIn + ") — " + (v != null ? v.toFriendlyString() : ""));
            inDetails.add("IN #" + i + ": " + in.getOutpoint().getHash().toString() + ":" + in.getOutpoint().getIndex());
            inDetails.add("   seq: " + in.getSequenceNumber() + (in.getSequenceNumber() < 0xfffffffeL ? " (RBF)" : ""));
            byte[] sigBytes = in.getScriptSig().getProgram();
            String sig = sigBytes.length == 0 ? "<empty>" : "0x" + org.bitcoinj.core.Utils.HEX.encode(sigBytes);
            inDetails.add("   scriptSig: " + sig);
            if (in.hasWitness()) {
                int pushCount = in.getWitness().getPushCount();
                inDetails.add("   witness [" + pushCount + " items]:");
                for (int j = 0; j < pushCount; j++) {
                    byte[] p = in.getWitness().getPush(j);
                    inDetails.add("     - 0x" + org.bitcoinj.core.Utils.HEX.encode(p));
                }
            }
        } catch (Exception e) {
        }
    }

    org.bitcoinj.core.Coin totalTo = org.bitcoinj.core.Coin.ZERO;
    java.util.List<String> toLines = new java.util.ArrayList<String>();
    java.util.List<String> outDetails = new java.util.ArrayList<String>();
    String opReturnData = null;

    for (int i = 0; i < tx.getOutputs().size(); i++) {
        org.bitcoinj.core.TransactionOutput out = tx.getOutputs().get(i);
        try {
            org.bitcoinj.core.Coin v = out.getValue();
            if (v != null) {
                totalTo = totalTo.add(v);
            }
            String addr = "unknown";
            String type = "nonstandard";
            try {
                if (out.getScriptPubKey().isOpReturn()) {
                    type = "OP_RETURN";
                    try {
                        opReturnData = new String(out.getScriptPubKey().getChunks().get(1).data, "UTF-8");
                    } catch (Exception e) {
                        opReturnData = "binary";
                    }
                } else {
                    org.bitcoinj.core.Address a = null;
                    try {
                        a = out.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
                    } catch (Exception e1) {
                        a = out.getScriptPubKey().getToAddress(org.bitcoinj.params.TestNet3Params.get());
                    }
                    if (a != null) {
                        addr = a.toString();
                        if (addr.startsWith("bc1q") || addr.startsWith("tb1q")) {
                            type = "P2WPKH";
                        } else if (addr.startsWith("bc1p") || addr.startsWith("tb1p")) {
                            type = "P2TR";
                        } else if (addr.startsWith("bc1") || addr.startsWith("tb1")) {
                            type = "P2WSH";
                        } else if (addr.startsWith("3") || addr.startsWith("2")) {
                            type = "P2SH";
                        } else if (addr.startsWith("1") || addr.startsWith("m") || addr.startsWith("n")) {
                            type = "P2PKH";
                        }
                    }
                }
            } catch (Exception e) {
            }
            toLines.add(addr + " — " + (v != null ? v.toFriendlyString() : ""));
            outDetails.add("OUT #" + i + ": " + type + " " + (v != null ? v.toFriendlyString() : ""));
            String scr = "0x" + org.bitcoinj.core.Utils.HEX.encode(out.getScriptPubKey().getProgram());
            outDetails.add("   script: " + scr);
            outDetails.add("   dust: " + out.isDust());
            try {
                outDetails.add("   spent: " + out.isAvailableForSpending());
            } catch (Exception e) {
            }
        } catch (Exception e) {
        }
    }

    java.util.List<String> advDetails = new java.util.ArrayList<String>();
    try {
        advDetails.add("Confidence: " + tx.getConfidence().getConfidenceType());
    } catch (Exception e) {
    }
    try {
        org.bitcoinj.core.Transaction over = tx.getConfidence().getOverridingTransaction();
        if (over != null) {
            advDetails.add("Overridden by: " + over.getTxId().toString());
        }
    } catch (Exception e) {
    }
    try {
        advDetails.add("Broadcast peers: " + tx.getConfidence().numBroadcastPeers());
    } catch (Exception e) {
    }
    try {
        advDetails.add("Source: " + tx.getConfidence().getSource());
    } catch (Exception e) {
    }
    try {
        advDetails.add("Purpose: " + tx.getPurpose());
    } catch (Exception e) {
    }
    try {
        if (tx.getMemo() != null && !tx.getMemo().isEmpty()) {
            advDetails.add("Memo: " + tx.getMemo());
        }
    } catch (Exception e) {
    }
    try {
        advDetails.add("SigOps: " + tx.getSigOpCount());
    } catch (Exception e) {
    }
    try {
        advDetails.add("Has witness: " + tx.hasWitnesses());
    } catch (Exception e) {
    }
    try {
        advDetails.add("Replaceable: " + (rbf ? "Yes (BIP125)" : "No"));
    } catch (Exception e) {
    }
    try {
        advDetails.add("Locktime enabled: " + (locktime > 0));
    } catch (Exception e) {
    }
    try {
        advDetails.add("Optimal size: " + tx.getOptimalEncodingMessageSize() + " B");
    } catch (Exception e) {
    }

    java.util.List<String> debugDetails = new java.util.ArrayList<String>();
    try {
        debugDetails.add("Hash: " + tx.getHash().toString());
    } catch (Exception e) {
    }
    try {
        debugDetails.add("TxId: " + tx.getTxId().toString());
    } catch (Exception e) {
    }
    try {
        debugDetails.add("WTxId: " + tx.getWTxId().toString());
    } catch (Exception e) {
    }
    try {
        debugDetails.add("Mature: " + tx.isMature());
    } catch (Exception e) {
    }
    try {
        tx.verify();
        debugDetails.add("Verify: PASS");
    } catch (Exception e) {
        debugDetails.add("Verify: FAIL");
    }
    try {
        debugDetails.add("Relevant: " + wallet.isTransactionRelevant(tx));
    } catch (Exception e) {
    }
    try {
        debugDetails.add("Value from me: " + tx.getValueSentFromMe(wallet).toFriendlyString());
    } catch (Exception e) {
    }
    try {
        debugDetails.add("Value to me: " + tx.getValueSentToMe(wallet).toFriendlyString());
    } catch (Exception e) {
    }
    try {
        if (fee != null) {
            debugDetails.add("Fee/weight: " + String.format(java.util.Locale.US, "%.2f", (double) fee.value / tx.getWeight()) + " sat/wu");
        }
    } catch (Exception e) {
    }
    try {
        debugDetails.add("Update time: " + timeStr);
    } catch (Exception e) {
    }
    for (int i = 0; i < tx.getInputs().size(); i++) {
        try {
            long seq = tx.getInput(i).getSequenceNumber();
            boolean bip68 = seq < 0xfffffffeL;
            debugDetails.add("IN#" + i + " BIP68: " + bip68);
        } catch (Exception e) {
        }
    }

    org.bitcoinj.core.Address actualReceiver = null;
    org.bitcoinj.core.Address actualSender = null;
    try {
        actualReceiver = txSent ? WalletUtils.getToAddressOfSent(tx, wallet) : WalletUtils.getWalletAddressOfReceived(tx, wallet);
    } catch (Exception e) {
    }
    try {
        org.bitcoinj.core.Coin max = org.bitcoinj.core.Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput in : tx.getInputs()) {
            org.bitcoinj.core.TransactionOutput c = in.getConnectedOutput();
            if (c != null && c.getValue() != null && c.getValue().isGreaterThan(max)) {
                max = c.getValue();
                try {
                    actualSender = c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
                } catch (Exception e1) {
                    actualSender = c.getScriptPubKey().getToAddress(org.bitcoinj.params.TestNet3Params.get());
                }
            }
        }
    } catch (Exception e) {
    }

    android.content.Context ctx = activity;
    float density = ctx.getResources().getDisplayMetrics().density;
    int pad = (int) (12 * density);
    int padTop = (int) (16 * density);
    int iconSize = (int) (20 * density);

    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.setClipToPadding(false);
    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(pad, (int) (24 * density), pad, pad);
    root.setClipToPadding(false);
    scroll.addView(root);

    final android.view.View.OnClickListener copyListener = new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            String txt = (String) v.getTag();
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("copy", txt));
            android.widget.Toast.makeText(ctx, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show();
        }
    };

    android.widget.LinearLayout header = new android.widget.LinearLayout(ctx);
    header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    header.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvTitle = new android.widget.TextView(ctx);
    tvTitle.setText(txSent ? "Sent" : "Receive");
    tvTitle.setTextSize(22);
    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    header.addView(tvTitle);
    android.widget.ImageView ivCopyTx = new android.widget.ImageView(ctx);
    ivCopyTx.setImageResource(R.drawable.ic_copy);
    ivCopyTx.setColorFilter(tvTitle.getCurrentTextColor());
    ivCopyTx.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    ivCopyTx.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));
    ivCopyTx.setTag((txSent ? "Sent" : "Receive") + "\n" + tx.getTxId().toString());
    ivCopyTx.setOnClickListener(copyListener);
    header.addView(ivCopyTx);
    root.addView(header);

    android.widget.TextView tvHash = new android.widget.TextView(ctx);
    tvHash.setText(tx.getTxId().toString());
    tvHash.setTextIsSelectable(true);
    tvHash.setSingleLine(true);
    tvHash.setHorizontallyScrolling(true);
    root.addView(tvHash);

    int iconColor = tvTitle.getCurrentTextColor();

    final String overviewTxt = "Time: " + timeStr + "\nConfirmations: " + confs + "\nBlock: " + (height > 0 ? height : "unconfirmed") + "\nStatus: " + status + "\nNet: " + (netValue != null ? netValue.toFriendlyString() : "-");
    String overviewFull = "OVERVIEW\n" + overviewTxt;
    android.widget.LinearLayout ovHead = new android.widget.LinearLayout(ctx);
    ovHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    ovHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvOv = new android.widget.TextView(ctx);
    tvOv.setText("OVERVIEW");
    tvOv.setTypeface(null, android.graphics.Typeface.BOLD);
    tvOv.setPadding(0, padTop, 0, 0);
    tvOv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    ovHead.addView(tvOv);
    android.widget.ImageView ivOv = new android.widget.ImageView(ctx);
    ivOv.setImageResource(R.drawable.ic_copy);
    ivOv.setColorFilter(iconColor);
    ivOv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpOv = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpOv.bottomMargin = (int) (2 * density);
    ivOv.setLayoutParams(lpOv);
    ivOv.setTag(overviewFull);
    ivOv.setOnClickListener(copyListener);
    ovHead.addView(ivOv);
    root.addView(ovHead);
    final android.widget.TextView tvOvInfo = new android.widget.TextView(ctx);
    tvOvInfo.setText(overviewTxt);
    tvOvInfo.setTextIsSelectable(true);
    root.addView(tvOvInfo);

    final String amountTxt = "Fee: " + (fee != null ? fee.toFriendlyString() : "0 BTC") + "\nFee rate: " + (fee != null ? String.format(java.util.Locale.US, "%.1f", (double) fee.value / vsize) : "-") + " sat/vB";
    String amountFull = "AMOUNT\n" + amountTxt;
    android.widget.LinearLayout amHead = new android.widget.LinearLayout(ctx);
    amHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    amHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvAm = new android.widget.TextView(ctx);
    tvAm.setText("AMOUNT");
    tvAm.setTypeface(null, android.graphics.Typeface.BOLD);
    tvAm.setPadding(0, padTop, 0, 0);
    tvAm.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    amHead.addView(tvAm);
    android.widget.ImageView ivAm = new android.widget.ImageView(ctx);
    ivAm.setImageResource(R.drawable.ic_copy);
    ivAm.setColorFilter(iconColor);
    ivAm.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpAm = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpAm.bottomMargin = (int) (2 * density);
    ivAm.setLayoutParams(lpAm);
    ivAm.setTag(amountFull);
    ivAm.setOnClickListener(copyListener);
    amHead.addView(ivAm);
    root.addView(amHead);
    final android.widget.TextView tvAmInfo = new android.widget.TextView(ctx);
    tvAmInfo.setText(amountTxt);
    tvAmInfo.setTextIsSelectable(true);
    root.addView(tvAmInfo);

    StringBuilder tech = new StringBuilder();
    tech.append("Version: ").append(version).append(" | Locktime: ").append(locktime).append("\n");
    tech.append("Size: ").append(tx.getMessageSize()).append(" B | vSize: ").append(vsize).append(" vB | Weight: ").append(tx.getWeight()).append("\n");
    tech.append("Inputs: ").append(tx.getInputs().size()).append(" | Outputs: ").append(tx.getOutputs().size()).append("\n");
    tech.append("RBF: ").append(rbf ? "Yes" : "No").append(" | SegWit: ").append(isSegWit ? "Yes" : "No").append(" | Coinbase: ").append(isCoinbase ? "Yes" : "No");
    if (opReturnData != null) {
        tech.append("\nOP_RETURN: ").append(opReturnData);
    }
    String techFull = "TECHNICAL\n" + tech.toString();
    android.widget.LinearLayout teHead = new android.widget.LinearLayout(ctx);
    teHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    teHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvTe = new android.widget.TextView(ctx);
    tvTe.setText("TECHNICAL");
    tvTe.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTe.setPadding(0, padTop, 0, 0);
    tvTe.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    teHead.addView(tvTe);
    android.widget.ImageView ivTe = new android.widget.ImageView(ctx);
    ivTe.setImageResource(R.drawable.ic_copy);
    ivTe.setColorFilter(iconColor);
    ivTe.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpTe = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpTe.bottomMargin = (int) (2 * density);
    ivTe.setLayoutParams(lpTe);
    ivTe.setTag(techFull);
    ivTe.setOnClickListener(copyListener);
    teHead.addView(ivTe);
    root.addView(teHead);
    android.widget.TextView tvTeInfo = new android.widget.TextView(ctx);
    tvTeInfo.setText(tech.toString());
    tvTeInfo.setTextIsSelectable(true);
    root.addView(tvTeInfo);

    StringBuilder fromAll = new StringBuilder();
    for (String s : fromLines) {
        fromAll.append(s).append("\n");
    }
    String fromTxt = "Total: " + totalFrom.toFriendlyString() + " from " + fromLines.size() + "\n" + fromAll.toString().trim();
    String fromFull = "FROM\n" + fromTxt;
    android.widget.LinearLayout frHead = new android.widget.LinearLayout(ctx);
    frHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    frHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvFr = new android.widget.TextView(ctx);
    tvFr.setText("FROM");
    tvFr.setTypeface(null, android.graphics.Typeface.BOLD);
    tvFr.setPadding(0, padTop, 0, 0);
    tvFr.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    frHead.addView(tvFr);
    android.widget.ImageView ivFr = new android.widget.ImageView(ctx);
    ivFr.setImageResource(R.drawable.ic_copy);
    ivFr.setColorFilter(iconColor);
    ivFr.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpFr = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpFr.bottomMargin = (int) (2 * density);
    ivFr.setLayoutParams(lpFr);
    ivFr.setTag(fromFull);
    ivFr.setOnClickListener(copyListener);
    frHead.addView(ivFr);
    root.addView(frHead);
    android.widget.TextView tvFrTot = new android.widget.TextView(ctx);
    tvFrTot.setText("Total: " + totalFrom.toFriendlyString() + " from " + fromLines.size());
    root.addView(tvFrTot);
    for (String l : fromLines) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(l);
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        tv.setTextIsSelectable(true);
        tv.setSingleLine(true);
        tv.setHorizontallyScrolling(true);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.ic_copy);
        iv.setColorFilter(iconColor);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));
        iv.setTag(l);
        iv.setOnClickListener(copyListener);
        row.addView(tv);
        row.addView(iv);
        root.addView(row);
    }

    StringBuilder toAll = new StringBuilder();
    for (String s : toLines) {
        toAll.append(s).append("\n");
    }
    String toTxt = "Total: " + totalTo.toFriendlyString() + " to " + toLines.size() + "\n" + toAll.toString().trim();
    String toFull = "TO\n" + toTxt;
    android.widget.LinearLayout toHead = new android.widget.LinearLayout(ctx);
    toHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    toHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvTo = new android.widget.TextView(ctx);
    tvTo.setText("TO");
    tvTo.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTo.setPadding(0, padTop, 0, 0);
    tvTo.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    toHead.addView(tvTo);
    android.widget.ImageView ivTo = new android.widget.ImageView(ctx);
    ivTo.setImageResource(R.drawable.ic_copy);
    ivTo.setColorFilter(iconColor);
    ivTo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpTo = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpTo.bottomMargin = (int) (2 * density);
    ivTo.setLayoutParams(lpTo);
    ivTo.setTag(toFull);
    ivTo.setOnClickListener(copyListener);
    toHead.addView(ivTo);
    root.addView(toHead);
    android.widget.TextView tvToTot = new android.widget.TextView(ctx);
    tvToTot.setText("Total: " + totalTo.toFriendlyString() + " to " + toLines.size());
    root.addView(tvToTot);
    for (String l : toLines) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(l);
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        tv.setTextIsSelectable(true);
        tv.setSingleLine(true);
        tv.setHorizontallyScrolling(true);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.ic_copy);
        iv.setColorFilter(iconColor);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));
        iv.setTag(l);
        iv.setOnClickListener(copyListener);
        row.addView(tv);
        row.addView(iv);
        root.addView(row);
    }

    StringBuilder det = new StringBuilder();
    for (String s : inDetails) {
        det.append(s).append("\n");
    }
    for (String s : outDetails) {
        det.append(s).append("\n");
    }
    String detFull = "DETAILS\n" + det.toString().trim();
    android.widget.LinearLayout deHead = new android.widget.LinearLayout(ctx);
    deHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    deHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvDe = new android.widget.TextView(ctx);
    tvDe.setText("DETAILS");
    tvDe.setTypeface(null, android.graphics.Typeface.BOLD);
    tvDe.setPadding(0, padTop, 0, 0);
    tvDe.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    deHead.addView(tvDe);
    android.widget.ImageView ivDe = new android.widget.ImageView(ctx);
    ivDe.setImageResource(R.drawable.ic_copy);
    ivDe.setColorFilter(iconColor);
    ivDe.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpDe = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpDe.bottomMargin = (int) (2 * density);
    ivDe.setLayoutParams(lpDe);
    ivDe.setTag(detFull);
    ivDe.setOnClickListener(copyListener);
    deHead.addView(ivDe);
    root.addView(deHead);
    for (String s : inDetails) {
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setText(s);
        d.setTextIsSelectable(true);
        d.setSingleLine(true);
        d.setHorizontallyScrolling(true);
        if (s.startsWith("   ")) {
            d.setPadding((int) (12 * density), 0, 0, 0);
        }
        root.addView(d);
    }
    for (String s : outDetails) {
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setText(s);
        d.setTextIsSelectable(true);
        d.setSingleLine(true);
        d.setHorizontallyScrolling(true);
        if (s.startsWith("   ")) {
            d.setPadding((int) (12 * density), 0, 0, 0);
        }
        root.addView(d);
    }

    StringBuilder advAll = new StringBuilder();
    for (String s : advDetails) {
        advAll.append(s).append("\n");
    }
    String advFull = "ADVANCED\n" + advAll.toString().trim();
    android.widget.LinearLayout adHead = new android.widget.LinearLayout(ctx);
    adHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    adHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvAd = new android.widget.TextView(ctx);
    tvAd.setText("ADVANCED");
    tvAd.setTypeface(null, android.graphics.Typeface.BOLD);
    tvAd.setPadding(0, padTop, 0, 0);
    tvAd.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    adHead.addView(tvAd);
    android.widget.ImageView ivAd = new android.widget.ImageView(ctx);
    ivAd.setImageResource(R.drawable.ic_copy);
    ivAd.setColorFilter(iconColor);
    ivAd.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpAd = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpAd.bottomMargin = (int) (2 * density);
    ivAd.setLayoutParams(lpAd);
    ivAd.setTag(advFull);
    ivAd.setOnClickListener(copyListener);
    adHead.addView(ivAd);
    root.addView(adHead);
    for (String s : advDetails) {
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setText(s);
        d.setTextIsSelectable(true);
        d.setSingleLine(true);
        d.setHorizontallyScrolling(true);
        root.addView(d);
    }

    StringBuilder debugAll = new StringBuilder();
    for (String s : debugDetails) {
        debugAll.append(s).append("\n");
    }
    String debugFull = "OFFLINE DEBUG\n" + debugAll.toString().trim();
    android.widget.LinearLayout dbHead = new android.widget.LinearLayout(ctx);
    dbHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    dbHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvDb = new android.widget.TextView(ctx);
    tvDb.setText("OFFLINE DEBUG");
    tvDb.setTypeface(null, android.graphics.Typeface.BOLD);
    tvDb.setPadding(0, padTop, 0, 0);
    tvDb.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    dbHead.addView(tvDb);
    android.widget.ImageView ivDb = new android.widget.ImageView(ctx);
    ivDb.setImageResource(R.drawable.ic_copy);
    ivDb.setColorFilter(iconColor);
    ivDb.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpDb = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpDb.bottomMargin = (int) (2 * density);
    ivDb.setLayoutParams(lpDb);
    ivDb.setTag(debugFull);
    ivDb.setOnClickListener(copyListener);
    dbHead.addView(ivDb);
    root.addView(dbHead);
    for (String s : debugDetails) {
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setText(s);
        d.setTextIsSelectable(true);
        d.setSingleLine(true);
        d.setHorizontallyScrolling(true);
        root.addView(d);
    }

    String walletTxt = "Actual Sender: " + (actualSender != null ? actualSender : "unknown") + "\nActual Receiver: " + (actualReceiver != null ? actualReceiver : "unknown");
    String walletFull = "WALLET\n" + walletTxt;
    android.widget.LinearLayout waHead = new android.widget.LinearLayout(ctx);
    waHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    waHead.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvWa = new android.widget.TextView(ctx);
    tvWa.setText("WALLET");
    tvWa.setTypeface(null, android.graphics.Typeface.BOLD);
    tvWa.setPadding(0, padTop, 0, 0);
    tvWa.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    waHead.addView(tvWa);
    android.widget.ImageView ivWa = new android.widget.ImageView(ctx);
    ivWa.setImageResource(R.drawable.ic_copy);
    ivWa.setColorFilter(iconColor);
    ivWa.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    android.widget.LinearLayout.LayoutParams lpWa = new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
    lpWa.bottomMargin = (int) (2 * density);
    ivWa.setLayoutParams(lpWa);
    ivWa.setTag(walletFull);
    ivWa.setOnClickListener(copyListener);
    waHead.addView(ivWa);
    root.addView(waHead);

    android.widget.LinearLayout rowSender = new android.widget.LinearLayout(ctx);
    rowSender.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    rowSender.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvAS = new android.widget.TextView(ctx);
    tvAS.setText("Actual Sender: " + (actualSender != null ? actualSender : "unknown"));
    tvAS.setTextIsSelectable(true);
    tvAS.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    tvAS.setSingleLine(true);
    tvAS.setHorizontallyScrolling(true);
    android.widget.ImageView ivAS = new android.widget.ImageView(ctx);
    ivAS.setImageResource(R.drawable.ic_copy);
    ivAS.setColorFilter(iconColor);
    ivAS.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    ivAS.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));
    ivAS.setTag(actualSender != null ? actualSender.toString() : "unknown");
    ivAS.setOnClickListener(copyListener);
    rowSender.addView(tvAS);
    rowSender.addView(ivAS);
    root.addView(rowSender);

    android.widget.LinearLayout rowReceiver = new android.widget.LinearLayout(ctx);
    rowReceiver.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    rowReceiver.setGravity(android.view.Gravity.BOTTOM);
    android.widget.TextView tvAR = new android.widget.TextView(ctx);
    tvAR.setText("Actual Receiver: " + (actualReceiver != null ? actualReceiver : "unknown"));
    tvAR.setTextIsSelectable(true);
    tvAR.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    tvAR.setSingleLine(true);
    tvAR.setHorizontallyScrolling(true);
    android.widget.ImageView ivAR = new android.widget.ImageView(ctx);
    ivAR.setImageResource(R.drawable.ic_copy);
    ivAR.setColorFilter(iconColor);
    ivAR.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
    ivAR.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSize, iconSize));
    ivAR.setTag(actualReceiver != null ? actualReceiver.toString() : "unknown");
    ivAR.setOnClickListener(copyListener);
    rowReceiver.addView(tvAR);
    rowReceiver.addView(ivAR);
    root.addView(rowReceiver);

    final StringBuilder plain = new StringBuilder();
    plain.append(txSent ? "Sent" : "Receive").append("\n");
    plain.append(tx.getTxId().toString()).append("\n\n");
    plain.append(overviewFull).append("\n\n");
    plain.append(amountFull).append("\n\n");
    plain.append(techFull).append("\n\n");
    plain.append(fromFull).append("\n\n");
    plain.append(toFull).append("\n\n");
    plain.append(detFull).append("\n\n");
    plain.append(advFull).append("\n\n");
    plain.append(debugFull).append("\n\n");
    plain.append(walletFull);

    new android.app.AlertDialog.Builder(activity).setView(scroll).setPositiveButton("OK", null).setNeutralButton("COPY ALL", new android.content.DialogInterface.OnClickListener() {
        public void onClick(android.content.DialogInterface d, int w) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("tx", plain.toString()));
            android.widget.Toast.makeText(ctx, "Copied all", android.widget.Toast.LENGTH_SHORT).show();
        }
    }).setNegativeButton("EXPLORER", new android.content.DialogInterface.OnClickListener() {
        public void onClick(android.content.DialogInterface d, int w) {
            try {
                activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mempool.space/tx/" + tx.getTxId())));
            } catch (Exception e) {
            }
        }
    }).show();
}


        
//end show transaction
        
    @Override
    public void onInflateTransactionContextMenu(final MenuInflater inflater, final Menu menu,
                                                final Sha256Hash transactionId) {
        final Wallet wallet = viewModel.wallet.getValue();
        final Transaction tx = wallet.getTransaction(transactionId);
        final boolean txSent = tx.getValue(wallet).signum() < 0;
        final Address txAddress = txSent ? WalletUtils.getToAddressOfSent(tx, wallet)
                : WalletUtils.getWalletAddressOfReceived(tx, wallet);
        final byte[] txSerialized = tx.unsafeBitcoinSerialize();

        inflater.inflate(R.menu.wallet_transactions_context, menu);
        final MenuItem editAddressMenuItem = menu
                .findItem(R.id.wallet_transactions_context_edit_address);
        if (txAddress != null) {
            editAddressMenuItem.setVisible(true);
            final boolean isAdd = addressBookDao.resolveLabel(txAddress.toString()) == null;
            final boolean isOwn = wallet.isAddressMine(txAddress);

            if (isOwn)
                editAddressMenuItem.setTitle(isAdd ? R.string.edit_address_book_entry_dialog_title_add_receive
                        : R.string.edit_address_book_entry_dialog_title_edit_receive);
            else
                editAddressMenuItem.setTitle(isAdd ? R.string.edit_address_book_entry_dialog_title_add
                        : R.string.edit_address_book_entry_dialog_title_edit);
        } else {
            editAddressMenuItem.setVisible(false);
        }

        menu.findItem(R.id.wallet_transactions_context_show_qr)
                .setVisible(txSerialized.length < SHOW_QR_THRESHOLD_BYTES);
        menu.findItem(R.id.wallet_transactions_context_raise_fee)
                .setVisible(RaiseFeeDialogFragment.feeCanLikelyBeRaised(wallet, tx));
        menu.findItem(R.id.wallet_transactions_context_browse).setVisible(Constants.ENABLE_BROWSE);   
     // add ,enu i 1/2
        menu.findItem(R.id.wallet_transactions_context_show_details).setVisible(true); // add menu i transaction
        //end add menu i
    }

    @Override
    public boolean onClickTransactionContextMenuItem(final MenuItem item, final Sha256Hash transactionId) {
        final Wallet wallet = viewModel.wallet.getValue();
        final Transaction tx = wallet.getTransaction(transactionId);
        final int itemId = item.getItemId();
        if (itemId == R.id.wallet_transactions_context_edit_address) {
            final boolean txSent = tx.getValue(wallet).signum() < 0;
            final Address txAddress = txSent ? WalletUtils.getToAddressOfSent(tx, wallet)
                    : WalletUtils.getWalletAddressOfReceived(tx, wallet);
            viewModel.showEditAddressBookEntryDialog.setValue(new Event<>(txAddress));
            return true;
        } else if (itemId == R.id.wallet_transactions_context_show_qr) {
            final byte[] txSerialized = tx.unsafeBitcoinSerialize();
            final Bitmap qrCodeBitmap = Qr.bitmap(Qr.encodeCompressBinary(txSerialized));
            viewModel.showBitmapDialog.setValue(new Event<>(qrCodeBitmap));
            return true;
        } else if (itemId == R.id.wallet_transactions_context_raise_fee) {
            RaiseFeeDialogFragment.show(fragmentManager, transactionId);
            return true;
        } else if (itemId == R.id.wallet_transactions_context_report_issue) {
            viewModel.showReportIssueDialog.setValue(new Event<>(transactionId));
            return true;
        } else if (itemId == R.id.wallet_transactions_context_browse) {
            final Uri blockExplorerUri = config.getBlockExplorer();
            log.info("Viewing transaction {} on {}", transactionId, blockExplorerUri);
            activity.startExternalDocument(Uri.withAppendedPath(blockExplorerUri, "tx/" + transactionId.toString()));
            return true;
                //add meniU i 2/2
               } else if (itemId == R.id.wallet_transactions_context_show_details) {
    showTransactionDetails(transactionId);
    return true; 
                //end add menu i
        } else {
            return false;
        }
    }

    @Override
    public void onWarningClick(final View view, final TransactionsAdapter.WarningType warning) {
        if (warning == TransactionsAdapter.WarningType.BACKUP)
            activityViewModel.showBackupWalletDialog.setValue(Event.simple());
        else if (warning == TransactionsAdapter.WarningType.STORAGE_ENCRYPTION)
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
    }

    private TransactionsAdapter.WarningType warning() {
        if (config.remindBackup())
            return TransactionsAdapter.WarningType.BACKUP;

        final int storageEncryptionStatus = devicePolicyManager.getStorageEncryptionStatus();
        if (storageEncryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE
                || storageEncryptionStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY)
            return TransactionsAdapter.WarningType.STORAGE_ENCRYPTION;

        return null;
    }
}
