package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * The installer's answer to SelfUpdate's session (not exported: only our PendingIntent).
 *
 * Asked for the person's OK (the first update from here, see SelfUpdate) it shows the
 * system's prompt. On success the app is replaced, which ends this process: nothing to do.
 * A failure says so; the old version stays.
 */
public class SelfUpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SelfUpdate.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (Throwable t) {
                Toast.makeText(context, R.string.update_err_install, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return; // the person said no
        Toast.makeText(context, R.string.update_err_install, Toast.LENGTH_LONG).show();
    }
}
