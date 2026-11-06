package eu.berdosi.app.heartbeat;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** One two-line row per logged reading: when, then pulse, activity, duration. */
final class LogAdapter extends BaseAdapter {
    private final Context context;
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private List<ReadingEntry> entries = new ArrayList<>();

    LogAdapter(Context context) {
        this.context = context;
    }

    void setEntries(List<ReadingEntry> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public ReadingEntry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView != null
                ? convertView
                : LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false);
        ReadingEntry entry = entries.get(position);
        TextView title = row.findViewById(android.R.id.text1);
        TextView subtitle = row.findViewById(android.R.id.text2);

        title.setText(dateFormat.format(new Date(entry.timestamp)));

        StringBuilder detail = new StringBuilder(context.getString(R.string.bpm_format, entry.bpm));
        if (entry.activity != null) {
            detail.append(" · ").append(ActivityPicker.label(context, entry.activity));
        }
        if (entry.durationMin != null) {
            detail.append(" · ").append(context.getString(R.string.minutes_format, entry.durationMin));
        }
        if (entry.isInvalid()) {
            detail.append(" · ").append(context.getString(R.string.log_invalid));
        }
        subtitle.setText(detail);

        // invalid readings stay in the list, struck through and dimmed
        markInvalid(title, entry.isInvalid());
        markInvalid(subtitle, entry.isInvalid());
        return row;
    }

    private static void markInvalid(TextView view, boolean invalid) {
        view.setAlpha(invalid ? 0.5f : 1f);
        if (invalid) view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        else view.setPaintFlags(view.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }
}
