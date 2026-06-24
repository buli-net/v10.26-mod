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
    if (tx == null) return;

    boolean txSent = false;
    try {
        txSent = wallet != null && tx.getValue(wallet).signum() < 0;
    } catch (Exception e) {}

    org.bitcoinj.core.Coin netValue = null;
    try {
        netValue = tx.getValue(wallet);
    } catch (Exception e) {}

    int confs = 0;
    try {
        confs = tx.getConfidence().getDepthInBlocks();
    } catch (Exception e) {}

    int height = 0;
    try {
        height = tx.getConfidence().getAppearedAtChainHeight();
    } catch (Exception e) {}

    org.bitcoinj.core.Coin fee = null;
    try {
        fee = tx.getFee();
    } catch (Exception e) {}

    boolean rbf = false;
    try {
        rbf = tx.isOptInFullRBF();
    } catch (Exception e) {}

    java.util.Date updateTime = null;
    try {
        updateTime = tx.getUpdateTime();
    } catch (Exception e) {}

    String timeStr = updateTime != null ? new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(updateTime) : "n/a";

    int vsize = (tx.getWeight() + 3) / 4;
    boolean isCoinbase = tx.isCoinBase();
    long version = tx.getVersion();
    long locktime = tx.getLockTime();

    boolean isSegWit = false;
    try {
        for (org.bitcoinj.core.TransactionOutput o : tx.getOutputs()) {
            try {
                org.bitcoinj.core.Address a = o.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
                if (a != null && a.toString().startsWith("bc1")) {
                    isSegWit = true;
                    break;
                }
            } catch (Exception e) {}
        }
    } catch (Exception e) {}

    String status = confs <= 0 ? "PENDING" : confs < 6 ? "BUILDING" : "CONFIRMED";

    org.bitcoinj.core.Coin totalFrom = org.bitcoinj.core.Coin.ZERO;
    java.util.List<String> fromLines = new java.util.ArrayList<>();
    java.util.List<String> inDetails = new java.util.ArrayList<>();
    for (org.bitcoinj.core.TransactionInput in : tx.getInputs()) {
        try {
            org.bitcoinj.core.TransactionOutput c = in.getConnectedOutput();
            org.bitcoinj.core.Coin v = c != null ? c.getValue() : null;
            if (v != null) totalFrom = totalFrom.add(v);
            String addr = "unknown";
            try {
                addr = c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS).toString();
            } catch (Exception e) {}
            fromLines.add(addr + " — " + (v != null ? v.toFriendlyString() : ""));
            inDetails.add("IN: " + in.getOutpoint().toString() + "\nSEQ: " + in.getSequenceNumber());
        } catch (Exception e) {}
    }

    org.bitcoinj.core.Coin totalTo = org.bitcoinj.core.Coin.ZERO;
    java.util.List<String> toLines = new java.util.ArrayList<>();
    java.util.List<String> outDetails = new java.util.ArrayList<>();
    String opReturnData = null;
    for (int i = 0; i < tx.getOutputs().size(); i++) {
        org.bitcoinj.core.TransactionOutput out = tx.getOutputs().get(i);
        try {
            org.bitcoinj.core.Coin v = out.getValue();
            if (v != null) totalTo = totalTo.add(v);
            String addr = "unknown";
            String type = "nonstandard";
            try {
                if (out.getScriptPubKey().isOpReturn()) {
                    type = "OP_RETURN";
                    try {
                        opReturnData = new String(out.getScriptPubKey().getChunks().get(1).data, "UTF-8");
                    } catch (Exception e) {}
                } else {
                    try {
                        org.bitcoinj.core.Address a = out.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
                        if (a != null) {
                            addr = a.toString();
                            if (addr.startsWith("bc1q")) type = "P2WPKH";
                            else if (addr.startsWith("bc1p")) type = "P2TR";
                            else if (addr.startsWith("bc1")) type = "P2WSH";
                            else if (addr.startsWith("3")) type = "P2SH";
                            else if (addr.startsWith("1")) type = "P2PKH";
                        }
                    } catch (Exception e) {}
                }
            } catch (Exception e) {}
            toLines.add(addr + " — " + (v != null ? v.toFriendlyString() : ""));
            outDetails.add("OUT: #" + i + " " + type + " " + (v != null ? v.toFriendlyString() : ""));
        } catch (Exception e) {}
    }

    org.bitcoinj.core.Address actualReceiver = null;
    org.bitcoinj.core.Address actualSender = null;
    try {
        actualReceiver = txSent ? WalletUtils.getToAddressOfSent(tx, wallet) : WalletUtils.getWalletAddressOfReceived(tx, wallet);
    } catch (Exception e) {}
    try {
        org.bitcoinj.core.Coin max = org.bitcoinj.core.Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput in : tx.getInputs()) {
            org.bitcoinj.core.TransactionOutput c = in.getConnectedOutput();
            if (c != null && c.getValue() != null && c.getValue().isGreaterThan(max)) {
                max = c.getValue();
                actualSender = c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
            }
        }
    } catch (Exception e) {}

    android.content.Context ctx = activity;
    float density = ctx.getResources().getDisplayMetrics().density;
    int pad = (int) (12 * density);
    int padTop = (int) (16 * density);
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(pad, pad, pad, pad);
    scroll.addView(root);

    final android.view.View.OnClickListener copyListener = new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            String txt = (String) v.getTag();
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("copy", txt));
            android.widget.Toast.makeText(ctx, "Đã copy", android.widget.Toast.LENGTH_SHORT).show();
        }
    };

    android.widget.LinearLayout header = new android.widget.LinearLayout(ctx);
    header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    header.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvTitle = new android.widget.TextView(ctx);
    tvTitle.setText(txSent ? "Sent" : "Receive");
    tvTitle.setTextSize(22);
    tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    header.addView(tvTitle);
    android.widget.ImageView ivCopyTx = new android.widget.ImageView(ctx);
    ivCopyTx.setImageResource(R.drawable.ic_copy);
    ivCopyTx.setColorFilter(tvTitle.getCurrentTextColor());
    ivCopyTx.setTag((txSent ? "Sent" : "Receive") + "\n" + tx.getTxId().toString());
    ivCopyTx.setOnClickListener(copyListener);
    header.addView(ivCopyTx);
    root.addView(header);

    android.widget.TextView tvHash = new android.widget.TextView(ctx);
    tvHash.setText(tx.getTxId().toString());
    tvHash.setTextIsSelectable(true);
    root.addView(tvHash);

    int iconColor = tvTitle.getCurrentTextColor();

    String overviewTxt = "Time: " + timeStr + "\nConfirmations: " + confs + "\nBlock: " + (height > 0 ? height : "unconfirmed") + "\nStatus: " + status + "\nNet: " + (netValue != null ? netValue.toFriendlyString() : "-");
    android.widget.LinearLayout ovHead = new android.widget.LinearLayout(ctx);
    ovHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    ovHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvOv = new android.widget.TextView(ctx);
    tvOv.setText("OVERVIEW");
    tvOv.setTypeface(null, android.graphics.Typeface.BOLD);
    tvOv.setPadding(0, padTop, 0, 0);
    tvOv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    ovHead.addView(tvOv);
    android.widget.ImageView ivOv = new android.widget.ImageView(ctx);
    ivOv.setImageResource(R.drawable.ic_copy);
    ivOv.setColorFilter(iconColor);
    ivOv.setTag(overviewTxt);
    ivOv.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpOv = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpOv.topMargin = padTop;
    ivOv.setLayoutParams(lpOv);
    ovHead.addView(ivOv);
    root.addView(ovHead);
    android.widget.TextView tvOvInfo = new android.widget.TextView(ctx);
    tvOvInfo.setText(overviewTxt);
    tvOvInfo.setTextIsSelectable(true);
    root.addView(tvOvInfo);

    String amountTxt = "Fee: " + (fee != null ? fee.toFriendlyString() : "0 BTC") + "\nFee rate: " + (fee != null ? String.format(java.util.Locale.US, "%.1f", (double) fee.value / vsize) : "-") + " sat/vB";
    android.widget.LinearLayout amHead = new android.widget.LinearLayout(ctx);
    amHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    amHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvAm = new android.widget.TextView(ctx);
    tvAm.setText("AMOUNT");
    tvAm.setTypeface(null, android.graphics.Typeface.BOLD);
    tvAm.setPadding(0, padTop, 0, 0);
    tvAm.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    amHead.addView(tvAm);
    android.widget.ImageView ivAm = new android.widget.ImageView(ctx);
    ivAm.setImageResource(R.drawable.ic_copy);
    ivAm.setColorFilter(iconColor);
    ivAm.setTag(amountTxt);
    ivAm.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpAm = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpAm.topMargin = padTop;
    ivAm.setLayoutParams(lpAm);
    amHead.addView(ivAm);
    root.addView(amHead);
    android.widget.TextView tvAmInfo = new android.widget.TextView(ctx);
    tvAmInfo.setText(amountTxt);
    tvAmInfo.setTextIsSelectable(true);
    root.addView(tvAmInfo);

    StringBuilder techSb = new StringBuilder();
    techSb.append("Version: ").append(version).append(" | Locktime: ").append(locktime).append("\n");
    techSb.append("Size: ").append(tx.getMessageSize()).append(" B | vSize: ").append(vsize).append(" vB | Weight: ").append(tx.getWeight()).append("\n");
    techSb.append("Inputs: ").append(tx.getInputs().size()).append(" | Outputs: ").append(tx.getOutputs().size()).append("\n");
    techSb.append("RBF: ").append(rbf ? "Yes" : "No").append(" | SegWit: ").append(isSegWit ? "Yes" : "No").append(" | Coinbase: ").append(isCoinbase ? "Yes" : "No");
    if (opReturnData != null) techSb.append("\nOP_RETURN: ").append(opReturnData);
    String techTxt = techSb.toString();
    android.widget.LinearLayout teHead = new android.widget.LinearLayout(ctx);
    teHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    teHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvTe = new android.widget.TextView(ctx);
    tvTe.setText("TECHNICAL");
    tvTe.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTe.setPadding(0, padTop, 0, 0);
    tvTe.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    teHead.addView(tvTe);
    android.widget.ImageView ivTe = new android.widget.ImageView(ctx);
    ivTe.setImageResource(R.drawable.ic_copy);
    ivTe.setColorFilter(iconColor);
    ivTe.setTag(techTxt);
    ivTe.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpTe = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpTe.topMargin = padTop;
    ivTe.setLayoutParams(lpTe);
    teHead.addView(ivTe);
    root.addView(teHead);
    android.widget.TextView tvTeInfo = new android.widget.TextView(ctx);
    tvTeInfo.setText(techTxt);
    tvTeInfo.setTextIsSelectable(true);
    root.addView(tvTeInfo);

    StringBuilder fromAllSb = new StringBuilder();
    for (String s : fromLines) fromAllSb.append(s).append("\n");
    String fromTxt = "Total: " + totalFrom.toFriendlyString() + " from " + fromLines.size() + "\n" + fromAllSb.toString().trim();
    android.widget.LinearLayout frHead = new android.widget.LinearLayout(ctx);
    frHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    frHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvFr = new android.widget.TextView(ctx);
    tvFr.setText("FROM");
    tvFr.setTypeface(null, android.graphics.Typeface.BOLD);
    tvFr.setPadding(0, padTop, 0, 0);
    tvFr.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    frHead.addView(tvFr);
    android.widget.ImageView ivFr = new android.widget.ImageView(ctx);
    ivFr.setImageResource(R.drawable.ic_copy);
    ivFr.setColorFilter(iconColor);
    ivFr.setTag(fromTxt);
    ivFr.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpFr = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpFr.topMargin = padTop;
    ivFr.setLayoutParams(lpFr);
    frHead.addView(ivFr);
    root.addView(frHead);
    android.widget.TextView tvFrTot = new android.widget.TextView(ctx);
    tvFrTot.setText("Total: " + totalFrom.toFriendlyString() + " from " + fromLines.size());
    root.addView(tvFrTot);
    for (String l : fromLines) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(l);
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        tv.setTextIsSelectable(true);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.ic_copy);
        iv.setColorFilter(iconColor);
        iv.setTag(l);
        iv.setOnClickListener(copyListener);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
        lp.topMargin = (int) (2 * density);
        iv.setLayoutParams(lp);
        row.addView(tv);
        row.addView(iv);
        root.addView(row);
    }

    StringBuilder toAllSb = new StringBuilder();
    for (String s : toLines) toAllSb.append(s).append("\n");
    String toTxt = "Total: " + totalTo.toFriendlyString() + " to " + toLines.size() + "\n" + toAllSb.toString().trim();
    android.widget.LinearLayout toHead = new android.widget.LinearLayout(ctx);
    toHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    toHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvTo = new android.widget.TextView(ctx);
    tvTo.setText("TO");
    tvTo.setTypeface(null, android.graphics.Typeface.BOLD);
    tvTo.setPadding(0, padTop, 0, 0);
    tvTo.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    toHead.addView(tvTo);
    android.widget.ImageView ivTo = new android.widget.ImageView(ctx);
    ivTo.setImageResource(R.drawable.ic_copy);
    ivTo.setColorFilter(iconColor);
    ivTo.setTag(toTxt);
    ivTo.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpTo = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpTo.topMargin = padTop;
    ivTo.setLayoutParams(lpTo);
    toHead.addView(ivTo);
    root.addView(toHead);
    android.widget.TextView tvToTot = new android.widget.TextView(ctx);
    tvToTot.setText("Total: " + totalTo.toFriendlyString() + " to " + toLines.size());
    root.addView(tvToTot);
    for (String l : toLines) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(l);
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        tv.setTextIsSelectable(true);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.ic_copy);
        iv.setColorFilter(iconColor);
        iv.setTag(l);
        iv.setOnClickListener(copyListener);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
        lp.topMargin = (int) (2 * density);
        iv.setLayoutParams(lp);
        row.addView(tv);
        row.addView(iv);
        root.addView(row);
    }

    StringBuilder detSb = new StringBuilder();
    for (String s : inDetails) detSb.append(s).append("\n");
    for (String s : outDetails) detSb.append(s).append("\n");
    String detailsTxt = detSb.toString().trim();
    android.widget.LinearLayout deHead = new android.widget.LinearLayout(ctx);
    deHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    deHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvDe = new android.widget.TextView(ctx);
    tvDe.setText("DETAILS");
    tvDe.setTypeface(null, android.graphics.Typeface.BOLD);
    tvDe.setPadding(0, padTop, 0, 0);
    tvDe.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    deHead.addView(tvDe);
    android.widget.ImageView ivDe = new android.widget.ImageView(ctx);
    ivDe.setImageResource(R.drawable.ic_copy);
    ivDe.setColorFilter(iconColor);
    ivDe.setTag(detailsTxt);
    ivDe.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpDe = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpDe.topMargin = padTop;
    ivDe.setLayoutParams(lpDe);
    deHead.addView(ivDe);
    root.addView(deHead);
    for (String s : inDetails) {
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setText(s);
        d.setTextIsSelectable(true);
        d.setSingleLine(false);
        root.addView(d);
    }
    for (String s : outDetails) {
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setText(s);
        d.setTextIsSelectable(true);
        d.setSingleLine(false);
        root.addView(d);
    }

    String walletTxt = "Actual Sender: " + (actualSender != null ? actualSender : "unknown") + "\nActual Receiver: " + (actualReceiver != null ? actualReceiver : "unknown");
    android.widget.LinearLayout waHead = new android.widget.LinearLayout(ctx);
    waHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    waHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
    android.widget.TextView tvWa = new android.widget.TextView(ctx);
    tvWa.setText("WALLET");
    tvWa.setTypeface(null, android.graphics.Typeface.BOLD);
    tvWa.setPadding(0, padTop, 0, 0);
    tvWa.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
    waHead.addView(tvWa);
    android.widget.ImageView ivWa = new android.widget.ImageView(ctx);
    ivWa.setImageResource(R.drawable.ic_copy);
    ivWa.setColorFilter(iconColor);
    ivWa.setTag(walletTxt);
    ivWa.setOnClickListener(copyListener);
    android.widget.LinearLayout.LayoutParams lpWa = new android.widget.LinearLayout.LayoutParams((int) (24 * density), (int) (24 * density));
    lpWa.topMargin = padTop;
    ivWa.setLayoutParams(lpWa);
    waHead.addView(ivWa);
    root.addView(waHead);
    android.widget.TextView tvAS = new android.widget.TextView(ctx);
    tvAS.setText("Actual Sender: " + (actualSender != null ? actualSender : "unknown"));
    tvAS.setTextIsSelectable(true);
    root.addView(tvAS);
    android.widget.TextView tvAR = new android.widget.TextView(ctx);
    tvAR.setText("Actual Receiver: " + (actualReceiver != null ? actualReceiver : "unknown"));
    tvAR.setTextIsSelectable(true);
    root.addView(tvAR);

    final StringBuilder plain = new StringBuilder();
    plain.append(txSent ? "Sent" : "Receive").append("\n").append(tx.getTxId()).append("\n\n");
    plain.append(overviewTxt).append("\n\n").append(amountTxt).append("\n\n").append(techTxt).append("\n\n");
    plain.append(fromTxt).append("\n\n").append(toTxt).append("\n\n").append(detailsTxt).append("\n\n").append(walletTxt);

    new android.app.AlertDialog.Builder(activity)
        .setView(scroll)
        .setPositiveButton("OK", null)
        .setNeutralButton("COPY ALL", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("tx", plain.toString()));
                android.widget.Toast.makeText(ctx, "Copied all", android.widget.Toast.LENGTH_SHORT).show();
            }
        })
        .setNegativeButton("EXPLORER", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                try {
                    activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mempool.space/tx/" + tx.getTxId())));
                } catch (Exception e) {}
            }
        })
        .show();
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
