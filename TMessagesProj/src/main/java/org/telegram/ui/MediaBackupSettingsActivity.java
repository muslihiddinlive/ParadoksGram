package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaBackupController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Sozlamalar: "Media bazasi" - foydalanuvchi o'zi tanlagan chat/kanalga
 * yangi media xabarlarni avtomatik forward qilish, qurilma o'chiq bo'lsa
 * ham. Foydalanuvchi aniq roziligisiz (ikki marta tasdiqlamasdan)
 * hech narsa yuborilmaydi.
 */
public class MediaBackupSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private static final int ROW_HEADER = 0;
    private static final int ROW_CONNECT_TOGGLE = 1;
    private static final int ROW_FORWARD_TOGGLE = 2;
    private static final int ROW_INFO = 3;

    private final ArrayList<Integer> rows = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("MediaBackupSettings", R.string.MediaBackupSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            int row = rows.get(position);
            if (row == ROW_CONNECT_TOGGLE) {
                onConnectToggleClicked();
            } else if (row == ROW_FORWARD_TOGGLE) {
                onForwardToggleClicked();
            }
        });

        updateRows();
        return fragmentView;
    }

    private void updateRows() {
        rows.clear();
        rows.add(ROW_HEADER);
        rows.add(ROW_CONNECT_TOGGLE);
        if (MediaBackupController.getInstance().isEnabled()) {
            rows.add(ROW_FORWARD_TOGGLE);
        }
        rows.add(ROW_INFO);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // "Media bazasini ulash" - alohida manzil so'ralmaydi, Sozlamalar -> ParadoksGram ->
    // "O'chirilgan media bazasi"da allaqachon tanlangan maqsad (ParadoksConfig.dbTarget)
    // to'g'ridan-to'g'ri ishlatiladi.
    private void onConnectToggleClicked() {
        boolean enabled = MediaBackupController.getInstance().isEnabled();
        if (enabled) {
            MediaBackupController.getInstance().disable(currentAccount, new MediaBackupController.Callback() {
                @Override
                public void onSuccess() {
                    updateRows();
                }

                @Override
                public void onError(String message) {
                    showError(message);
                }
            });
            return;
        }
        long target = uz.paradoks.gram.ParadoksConfig.dbTarget(currentAccount);
        if (target == 0) {
            showError("Avval \"Media bazasi\"ni tanlang: Sozlamalar → ParadoksGram → \"O'chirilgan media bazasi\".");
            return;
        }
        confirmConnect(target);
    }

    private void confirmConnect(long targetChatId) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String name = uz.paradoks.gram.ParadoksDeletedDbActivity.nameOf(getMessagesController(), targetChatId);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString("MediaBackupConnectTitle", R.string.MediaBackupConnectTitle));
        builder.setMessage("Media bazasi sifatida allaqachon tanlangan \"" + name + "\" ishlatiladi. Ulansinmi?");
        builder.setPositiveButton(LocaleController.getString("Connect", R.string.Connect), (dialog, which) -> {
            MediaBackupController.getInstance().enable(currentAccount, targetChatId, new MediaBackupController.Callback() {
                @Override
                public void onSuccess() {
                    updateRows();
                }

                @Override
                public void onError(String message) {
                    showError(message);
                }
            });
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    // "Avtomatik forward" - ikki marta tasdiqlangandan keyingina yoqiladi
    private void onForwardToggleClicked() {
        confirmStep1();
    }

    private void confirmStep1() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString("MediaBackupForwardTitle", R.string.MediaBackupForwardTitle));
        builder.setMessage(LocaleController.getString("MediaBackupForwardConfirm1", R.string.MediaBackupForwardConfirm1));
        builder.setPositiveButton(LocaleController.getString("Continue", R.string.Continue), (dialog, which) -> confirmStep2());
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmStep2() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString("MediaBackupForwardTitle", R.string.MediaBackupForwardTitle));
        builder.setMessage(LocaleController.getString("MediaBackupForwardConfirm2", R.string.MediaBackupForwardConfirm2));
        builder.setPositiveButton(LocaleController.getString("Enable", R.string.Enable), (dialog, which) -> {
            long targetChatId = MediaBackupController.getInstance().getTargetChatId();
            MediaBackupController.getInstance().enable(currentAccount, targetChatId, new MediaBackupController.Callback() {
                @Override
                public void onSuccess() {
                    Toast.makeText(context, LocaleController.getString("MediaBackupEnabled", R.string.MediaBackupEnabled), Toast.LENGTH_SHORT).show();
                    updateRows();
                }

                @Override
                public void onError(String message) {
                    showError(message);
                }
            });
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showError(String message) {
        Context context = getParentActivity();
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == ROW_HEADER) {
                view = new HeaderCell(getContext());
            } else if (viewType == ROW_INFO) {
                view = new TextInfoPrivacyCell(getContext());
            } else {
                view = new TextCheckCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int row = rows.get(position);
            if (row == ROW_HEADER) {
                ((HeaderCell) holder.itemView).setText(LocaleController.getString("MediaBackupHeader", R.string.MediaBackupHeader));
            } else if (row == ROW_CONNECT_TOGGLE) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(
                        LocaleController.getString("MediaBackupConnectRow", R.string.MediaBackupConnectRow),
                        MediaBackupController.getInstance().isEnabled(),
                        MediaBackupController.getInstance().isEnabled()
                );
            } else if (row == ROW_FORWARD_TOGGLE) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(
                        LocaleController.getString("MediaBackupForwardRow", R.string.MediaBackupForwardRow),
                        MediaBackupController.getInstance().isEnabled(),
                        false
                );
            } else if (row == ROW_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString("MediaBackupInfo", R.string.MediaBackupInfo));
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == ROW_CONNECT_TOGGLE || viewType == ROW_FORWARD_TOGGLE;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position);
        }
    }
}
