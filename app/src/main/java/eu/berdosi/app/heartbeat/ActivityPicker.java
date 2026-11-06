package eu.berdosi.app.heartbeat;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

/**
 * A spinner over {@link ActivityLevel} with a leading "not set" item. The listener only hears
 * about the user's picks, never about {@link #set}.
 */
final class ActivityPicker {
    interface Listener {
        void onPicked(ActivityLevel level);
    }

    private final Spinner spinner;
    private ActivityLevel current;

    ActivityPicker(Spinner spinner, Listener listener) {
        this.spinner = spinner;
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                spinner.getContext(), R.array.activity_levels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // setSelection() reports back asynchronously, so a programmatic change is
                // recognised by already being the current value
                ActivityLevel picked = position == 0 ? null : ActivityLevel.values()[position - 1];
                if (picked == current) return;
                current = picked;
                listener.onPicked(picked);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    void set(ActivityLevel level) {
        current = level;
        spinner.setSelection(level == null ? 0 : level.ordinal() + 1);
    }

    static String label(Context context, ActivityLevel level) {
        return context.getResources().getStringArray(R.array.activity_levels)[
                level == null ? 0 : level.ordinal() + 1];
    }
}
