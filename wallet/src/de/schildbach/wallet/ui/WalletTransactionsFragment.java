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
    try { txSent = wallet != null && tx.getValue(wallet).signum() < 0; } catch(Exception e) {}

    int confs = 0; try { confs = tx.getConfidence().getDepthInBlocks(); } catch(Exception e){}
    int height = 0; try { height = tx.getConfidence().getAppearedAtChainHeight(); } catch(Exception e){}
    org.bitcoinj.core.Coin fee = null; try { fee = tx.getFee(); } catch(Exception e){}
    boolean rbf = false; try { rbf = tx.isOptInFullRBF(); } catch(Exception e){}

    java.util.Date updateTime = null; try { updateTime = tx.getUpdateTime(); } catch(Exception e){}
    String timeStr = updateTime != null ? new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(updateTime) : "n/a";

    boolean isSegWit = false;
    try {
        for (org.bitcoinj.core.TransactionInput in : tx.getInputs()) {
            org.bitcoinj.core.TransactionOutput c = in.getConnectedOutput();
            if (c!=null) {
                org.bitcoinj.core.Address a = c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
                if (a!=null && a.toString().startsWith("bc1")) { isSegWit=true; break; }
            }
        }
    } catch(Exception e){}

    String status = confs<=0?"PENDING":confs<6?"BUILDING":"CONFIRMED";
    String statusColor = status.equals("PENDING")?"#FFA726":status.equals("BUILDING")?"#29B6F6":"#66BB6A";

    org.bitcoinj.core.Coin totalFrom = org.bitcoinj.core.Coin.ZERO;
    java.util.List<String> fromLines = new java.util.ArrayList<>();
    for(org.bitcoinj.core.TransactionInput in: tx.getInputs()){
        try{ org.bitcoinj.core.TransactionOutput c=in.getConnectedOutput(); if(c!=null){ org.bitcoinj.core.Coin v=c.getValue(); if(v!=null) totalFrom=totalFrom.add(v); String a="unknown"; try{a=c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS).toString();}catch(Exception e){} fromLines.add(a+" — "+(v!=null?v.toFriendlyString():"")); } }catch(Exception e){}
    }
    org.bitcoinj.core.Coin totalTo = org.bitcoinj.core.Coin.ZERO;
    java.util.List<String> toLines = new java.util.ArrayList<>();
    for(org.bitcoinj.core.TransactionOutput out: tx.getOutputs()){
        try{ org.bitcoinj.core.Coin v=out.getValue(); if(v!=null) totalTo=totalTo.add(v); String a="unknown"; try{a=out.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS).toString();}catch(Exception e){} toLines.add(a+" — "+(v!=null?v.toFriendlyString():"")); }catch(Exception e){}
    }
    org.bitcoinj.core.Address actualReceiver=null, actualSender=null;
    try{ actualReceiver = txSent? WalletUtils.getToAddressOfSent(tx,wallet) : WalletUtils.getWalletAddressOfReceived(tx,wallet); }catch(Exception e){}
    try{ org.bitcoinj.core.Coin max=org.bitcoinj.core.Coin.ZERO; for(org.bitcoinj.core.TransactionInput in:tx.getInputs()){ org.bitcoinj.core.TransactionOutput c=in.getConnectedOutput(); if(c!=null&&c.getValue()!=null&&c.getValue().isGreaterThan(max)){max=c.getValue(); actualSender=c.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);} } }catch(Exception e){}

    android.content.Context ctx = activity;
    int pad = (int)(12*ctx.getResources().getDisplayMetrics().density);
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(pad,pad,pad,pad);
    scroll.addView(root);

    android.view.View.OnClickListener copyListener = new android.view.View.OnClickListener(){
        @Override public void onClick(android.view.View v){
            String txt=(String)v.getTag();
            android.content.ClipboardManager cm=(android.content.ClipboardManager)ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("copy",txt));
            android.widget.Toast.makeText(ctx,"Đã copy",android.widget.Toast.LENGTH_SHORT).show();
        }
    };

    // HEADER
    android.widget.LinearLayout header = new android.widget.LinearLayout(ctx);
    header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    android.widget.TextView tvTitle = new android.widget.TextView(ctx);
    tvTitle.setText(txSent?"Sent":"Receive");
    tvTitle.setTextSize(22); tvTitle.setTypeface(null,android.graphics.Typeface.BOLD);
    tvTitle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
    header.addView(tvTitle);
    android.widget.ImageView ivCopyTx = new android.widget.ImageView(ctx);
    ivCopyTx.setImageResource(R.drawable.ic_copy);
    ivCopyTx.setColorFilter(tvTitle.getCurrentTextColor());
    ivCopyTx.setTag((txSent?"Sent":"Receive")+"\n"+tx.getTxId().toString());
    ivCopyTx.setOnClickListener(copyListener);
    header.addView(ivCopyTx);
    root.addView(header);

    android.widget.TextView tvHash = new android.widget.TextView(ctx);
    tvHash.setText(tx.getTxId().toString()); tvHash.setTextIsSelectable(true);
    root.addView(tvHash);

    // INFO
    StringBuilder info = new StringBuilder();
    info.append("<br><b>Time:</b> ").append(timeStr).append("<br>");
    info.append("<b>Confirmations:</b> ").append(confs).append("<br>");
    info.append("<b>Block:</b> ").append(height>0?height:"unconfirmed").append("<br><br>");
    info.append("<b>Fee:</b> ").append(fee!=null?fee.toFriendlyString():"0 BTC").append("<br>");
    try{ int vsize=(tx.getWeight()+3)/4; if(fee!=null) info.append("<b>Fee rate:</b> ").append(String.format(java.util.Locale.US,"%.1f",(double)fee.value/vsize)).append(" sat/vB<br>"); }catch(Exception e){}
    info.append("<b>Size:</b> ").append(tx.getMessageSize()).append(" bytes | <b>Weight:</b> ").append(tx.getWeight()).append("<br>");
    info.append("<b>RBF:</b> ").append(rbf?"Yes":"No").append(" | <b>SegWit:</b> ").append(isSegWit?"Yes":"No").append("<br><br>");
    info.append("<b>Status:</b> <font color='").append(statusColor).append("'>").append(status).append("</font><br><br>");
    android.widget.TextView tvInfo = new android.widget.TextView(ctx);
    tvInfo.setText(android.text.Html.fromHtml(info.toString(),android.text.Html.FROM_HTML_MODE_LEGACY));
    root.addView(tvInfo);

    int iconColor = tvTitle.getCurrentTextColor();

    // FROM
    StringBuilder fromAll = new StringBuilder();
    fromAll.append("Total: ").append(totalFrom.toFriendlyString()).append(" from ").append(fromLines.size()).append("\n\n");
    for(String l: fromLines) fromAll.append(l).append("\n");

    android.widget.LinearLayout fromHead = new android.widget.LinearLayout(ctx);
    fromHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    android.widget.TextView tvFromH = new android.widget.TextView(ctx);
    tvFromH.setText("FROM"); tvFromH.setTypeface(null,android.graphics.Typeface.BOLD);
    tvFromH.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
    fromHead.addView(tvFromH);
    android.widget.ImageView ivFromAll = new android.widget.ImageView(ctx);
    ivFromAll.setImageResource(R.drawable.ic_copy); ivFromAll.setColorFilter(iconColor);
    ivFromAll.setTag("FROM\n\n"+fromAll.toString().trim()); ivFromAll.setOnClickListener(copyListener);
    fromHead.addView(ivFromAll);
    root.addView(fromHead);

    android.widget.TextView tvFromTot = new android.widget.TextView(ctx);
    tvFromTot.setText("Total: "+totalFrom.toFriendlyString()+" from "+fromLines.size());
    root.addView(tvFromTot);

    for(String l: fromLines){
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(l); tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.ic_copy); iv.setColorFilter(iconColor);
        iv.setTag("FROM\n"+l); iv.setOnClickListener(copyListener);
        row.addView(tv); row.addView(iv); root.addView(row);
    }

    // TO
    StringBuilder toAll = new StringBuilder();
    toAll.append("Total: ").append(totalTo.toFriendlyString()).append(" to ").append(toLines.size()).append("\n\n");
    for(String l: toLines) toAll.append(l).append("\n");

    android.widget.LinearLayout toHead = new android.widget.LinearLayout(ctx);
    toHead.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    android.widget.TextView tvToH = new android.widget.TextView(ctx);
    tvToH.setText("TO"); tvToH.setTypeface(null,android.graphics.Typeface.BOLD);
    tvToH.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
    toHead.addView(tvToH);
    android.widget.ImageView ivToAll = new android.widget.ImageView(ctx);
    ivToAll.setImageResource(R.drawable.ic_copy); ivToAll.setColorFilter(iconColor);
    ivToAll.setTag("TO\n\n"+toAll.toString().trim()); ivToAll.setOnClickListener(copyListener);
    toHead.addView(ivToAll);
    root.addView(toHead);

    android.widget.TextView tvToTot = new android.widget.TextView(ctx);
    tvToTot.setText("Total: "+totalTo.toFriendlyString()+" to "+toLines.size());
    root.addView(tvToTot);

    for(String l: toLines){
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView tv = new android.widget.TextView(ctx);
        tv.setText(l); tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageResource(R.drawable.ic_copy); iv.setColorFilter(iconColor);
        iv.setTag("TO\n"+l); iv.setOnClickListener(copyListener);
        row.addView(tv); row.addView(iv); root.addView(row);
    }

    // Actual Sender
    android.widget.TextView tvAS = new android.widget.TextView(ctx); tvAS.setText("Actual Sender:"); tvAS.setTypeface(null,android.graphics.Typeface.BOLD); root.addView(tvAS);
    android.widget.LinearLayout rowAS = new android.widget.LinearLayout(ctx); rowAS.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    android.widget.TextView tvASv = new android.widget.TextView(ctx); tvASv.setText(actualSender!=null?actualSender.toString():"unknown"); tvASv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
    android.widget.ImageView ivAS = new android.widget.ImageView(ctx); ivAS.setImageResource(R.drawable.ic_copy); ivAS.setColorFilter(iconColor);
    ivAS.setTag("Actual Sender:\n"+(actualSender!=null?actualSender:"unknown")); ivAS.setOnClickListener(copyListener);
    rowAS.addView(tvASv); rowAS.addView(ivAS); root.addView(rowAS);

    // Actual Receiver
    android.widget.TextView tvAR = new android.widget.TextView(ctx); tvAR.setText("Actual Receiver:"); tvAR.setTypeface(null,android.graphics.Typeface.BOLD); root.addView(tvAR);
    android.widget.LinearLayout rowAR = new android.widget.LinearLayout(ctx); rowAR.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    android.widget.TextView tvARv = new android.widget.TextView(ctx); tvARv.setText(actualReceiver!=null?actualReceiver.toString():"unknown"); tvARv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,-2,1));
    android.widget.ImageView ivAR = new android.widget.ImageView(ctx); ivAR.setImageResource(R.drawable.ic_copy); ivAR.setColorFilter(iconColor);
    ivAR.setTag("Actual Receiver:\n"+(actualReceiver!=null?actualReceiver:"unknown")); ivAR.setOnClickListener(copyListener);
    rowAR.addView(tvARv); rowAR.addView(ivAR); root.addView(rowAR);

    // COPY ALL text
    StringBuilder plain = new StringBuilder();
    plain.append(txSent?"Sent":"Receive").append("\n").append(tx.getTxId()).append("\n\n");
    plain.append("Time: ").append(timeStr).append("\n");
    plain.append("Confirmations: ").append(confs).append("\n");
    plain.append("Block: ").append(height>0?height:"unconfirmed").append("\n\n");
    plain.append("Fee: ").append(fee!=null?fee.toFriendlyString():"0 BTC").append("\n");
    try{ int vsize=(tx.getWeight()+3)/4; if(fee!=null) plain.append("Fee rate: ").append(String.format(java.util.Locale.US,"%.1f",(double)fee.value/vsize)).append(" sat/vB\n"); }catch(Exception e){}
    plain.append("Size: ").append(tx.getMessageSize()).append(" bytes | Weight: ").append(tx.getWeight()).append("\n");
    plain.append("RBF: ").append(rbf?"Yes":"No").append(" | SegWit: ").append(isSegWit?"Yes":"No").append("\n");
    plain.append("Status: ").append(status).append("\n\n");
    plain.append("FROM\n\n").append(fromAll).append("\n");
    plain.append("TO\n\n").append(toAll).append("\n");
    plain.append("Actual Sender:\n").append(actualSender!=null?actualSender:"unknown").append("\n");
    plain.append("Actual Receiver:\n").append(actualReceiver!=null?actualReceiver:"unknown");
    final String plainAll = plain.toString();

    new android.app.AlertDialog.Builder(activity)
        .setView(scroll)
        .setPositiveButton("OK", null)
        .setNeutralButton("COPY ALL", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("tx", plainAll));
                android.widget.Toast.makeText(ctx, "Copied all", android.widget.Toast.LENGTH_SHORT).show();
            }
        })
        .setNegativeButton("EXPLORER", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                try { activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://mempool.space/tx/" + tx.getTxId()))); } catch (Exception e) {}
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
