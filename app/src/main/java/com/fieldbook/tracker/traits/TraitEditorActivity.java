package com.fieldbook.tracker.traits;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;

import com.fieldbook.tracker.ConfigActivity;
import com.fieldbook.tracker.brapi.BrapiInfoDialog;
import com.fieldbook.tracker.utilities.Utils;
import com.fieldbook.tracker.brapi.BrapiTraitActivity;
import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetSequence;
import com.google.android.material.floatingactionbutton.FloatingActionButton;


import android.provider.OpenableColumns;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout.LayoutParams;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import android.widget.ListView;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.fieldbook.tracker.io.CSVReader;
import com.fieldbook.tracker.io.CSVWriter;
import com.fieldbook.tracker.preferences.PreferencesActivity;
import com.fieldbook.tracker.utilities.Constants;
import com.fieldbook.tracker.DataHelper;
import com.fieldbook.tracker.FileExploreActivity;
import com.fieldbook.tracker.MainActivity;
import com.fieldbook.tracker.R;
import com.fieldbook.tracker.dragsort.DragSortListView;
import com.fieldbook.tracker.dragsort.DragSortController;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class TraitEditorActivity extends AppCompatActivity {

    public static DragSortListView traitList;
    public static TraitAdapter mAdapter;
    public static boolean brapiDialogShown = false;
    public static Activity thisActivity;
    private static Handler mHandler = new Handler();
    private static String mChosenFile;

    private static SharedPreferences ep;

    private static OnItemClickListener traitListener;
    private static DragSortListView.RemoveListener onRemove = new DragSortListView.RemoveListener() {
        @Override
        public void remove(int which) {
            mAdapter.list.remove(which);
        }
    };
    private static DragSortListView.DropListener onDrop = new DragSortListView.DropListener() {

        @Override
        public void drop(int from, int to) {

            // The new logic is that the drop point is now the center of the list. Any items before / after are reordered based on the drop point's position.
            // This means while the realposition may not start from zero, the ordering will always be correct.

            Log.w("ReorderStart", "start");

            if (from != to) {
                if (to > from) {
                    Log.w("Downward", "drag");

                    try {
                        // e.g. 4
                        String prevID = mAdapter.getItem(from).getId();

                        // e.g. 6
                        String currentID = mAdapter.getItem(to).getId();
                        String currentPosition = mAdapter.getItem(to).getRealPosition();

                        ConfigActivity.dt.updateTraitPosition(currentID, currentPosition);
                        ConfigActivity.dt.updateTraitPosition(prevID, String.valueOf(Integer.parseInt(currentPosition) + 1));

                        // Push everything below down by 1
                        int newCount = 2;

                        for (int i = to + 1; i < mAdapter.getCount(); i++) {
                            ConfigActivity.dt.updateTraitPosition(mAdapter.getItem(i).getId(), String.valueOf(Integer.parseInt(currentPosition) + newCount));
                            newCount++;
                        }

                        MainActivity.reloadData = true;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    Log.w("Upward", "drag");

                    try {
                        // upward drag
                        // e.g. 4
                        String prevID = mAdapter.getItem(from).getId();

                        // e.g. 6
                        String currentPosition = mAdapter.getItem(to).getRealPosition();

                        if (Integer.parseInt(currentPosition) - to >= 0) {
                            Log.w("Reorder", "top1");

                            // Reorder everything above
                            int newCount = Integer.parseInt(currentPosition) - to;

                            for (int i = 0; i < to; i++) {
                                ConfigActivity.dt.updateTraitPosition(mAdapter.getItem(i).getId(), String.valueOf(newCount));
                                newCount++;
                            }

                            Log.w("Reorder", "current");

                            ConfigActivity.dt.updateTraitPosition(prevID, currentPosition);

                        } else {
                            // We hit a -1, might as well do a full zero based reorder
                            // Reorder everything above

                            Log.w("Reorder", "top2");

                            for (int i = 0; i < to; i++) {
                                ConfigActivity.dt.updateTraitPosition(mAdapter.getItem(i).getId(), String.valueOf(i));
                            }

                            Log.w("Reorder", "current");

                            ConfigActivity.dt.updateTraitPosition(prevID, String.valueOf(to));

                            // Reset current position as well, otherwise we don't know where it points to
                            currentPosition = String.valueOf(to);
                        }

                        Log.w("Reorder", "below");

                        // Push everything below down by 1
                        int newCount = 1;

                        // last pulled position is from field

                        for (int i = to; i < mAdapter.getCount(); i++) {
                            if (i != from) {
                                ConfigActivity.dt.updateTraitPosition(mAdapter.getItem(i).getId(), String.valueOf(Integer.parseInt(currentPosition) + newCount));
                                newCount++;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

            }

            MainActivity.reloadData = true;
            loadData();
        }
    };
    private final int PERMISSIONS_REQUEST_STORAGE_IMPORT = 999;
    private final int PERMISSIONS_REQUEST_STORAGE_EXPORT = 998;
    private NewTraitDialog traitDialog;
    private Menu systemMenu;
    // Creates a new thread to do importing
    private Runnable importCSV = new Runnable() {
        public void run() {
            new ImportCSVTask().execute(0);
        }
    };

    // Helper function to load data
    public static void loadData() {
        try {

            HashMap visibility = ConfigActivity.dt.getTraitVisibility();

            if (!traitList.isShown())
                traitList.setVisibility(ListView.VISIBLE);

            // Determine if our BrAPI dialog was shown with our current trait adapter
            Boolean showBrapiDialog;
            if (mAdapter != null) {
                // Check if current trait adapter has shown a dialog
                brapiDialogShown = !brapiDialogShown ? mAdapter.infoDialogShown : brapiDialogShown;
            } else {
                // We should show our brapi dialog if this is our creating of the mAdapter
                brapiDialogShown = false;
            }

            mAdapter = new TraitAdapter(thisActivity, R.layout.listitem_trait, ConfigActivity.dt.getAllTraitObjects(), traitListener, visibility, brapiDialogShown);

            traitList.setAdapter(mAdapter);
            traitList.setDropListener(onDrop);
            traitList.setRemoveListener(onRemove);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Boolean displayBrapiInfo(Context context, DataHelper dt, String traitName, Boolean noCheckTrait) {

        // Returns true if the dialog is shown, false if not.
        // If we run into an error, do not warn the user since this is just a helper dialog
        try {
            // Check if this is a non-BrAPI field
            String fieldName = context.getSharedPreferences("Settings", 0)
                    .getString("FieldFile", "");
            String fieldSource = context.getSharedPreferences("Settings", 0)
                    .getString("ImportExpSource", "");

            if (!fieldName.equals("") && !fieldSource.equals("local") && !fieldSource.equals("")) {

                // noCheckTrait is used when the trait should not be checked, but the dialog
                // should be shown.
                if (noCheckTrait) {

                    BrapiInfoDialog brapiInfo = new BrapiInfoDialog(context, context.getResources().getString(R.string.brapi_info_message));
                    brapiInfo.show();
                    return true;
                }

                // Check if this is a BrAPI trait
                if (traitName != null) {

                    // Just returns an empty trait object in the case the trait isn't found
                    TraitObject trait = dt.getDetail(traitName);
                    if (trait.getTrait() == null) {
                        return false;
                    }

                    if (trait.getExternalDbId() == null || trait.getExternalDbId().equals("local") || trait.getExternalDbId().equals("")) {

                        // Show info dialog if a BrAPI field is selected.
                        BrapiInfoDialog brapiInfo = new BrapiInfoDialog(context, context.getResources().getString(R.string.brapi_info_message));
                        brapiInfo.show();

                        // Only show the info dialog on the first non-BrAPI trait selected.
                        return true;

                    } else {
                        // Dialog was not shown
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            Log.e("error", e.toString());
            return false;
        }

        return false;
    }

    // getter (get them from NewTraitDialog)
    public TraitAdapter getAdapter() {
        return mAdapter;
    }

    public SharedPreferences getPreferences() {
        return ep;
    }

    public boolean getBrAPIDialogShown() {
        return brapiDialogShown;
    }

    // when this value changes in NewTraitDialog,
    // the value in this class must change
    public void setBrAPIDialogShown(boolean b) {
        brapiDialogShown = b;
    }

    // because AlertDialog can't use getString
    public String getResourceString(int id) {
        return getString(id);
    }

    @Override
    public void onDestroy() {
        try {
            thisActivity.finish();
        } catch (Exception e) {
            String TAG = "Field Book";
            Log.e(TAG, "" + e.getMessage());
        }

        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (systemMenu != null) {
            systemMenu.findItem(R.id.help).setVisible(ep.getBoolean("Tips", false));
        }

        loadData();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ep = getSharedPreferences("Settings", 0);

        setContentView(R.layout.activity_traits);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.settings_traits));
            getSupportActionBar().getThemedContext();
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        Toolbar toolbar = (Toolbar) this.findViewById(R.id.toolbar);

        thisActivity = this;

        if (ConfigActivity.dt == null) {    // when resuming
            ConfigActivity.dt = new DataHelper(this);
        }
        HashMap visibility = ConfigActivity.dt.getTraitVisibility();
        traitList = findViewById(R.id.myList);

        if (!traitList.isShown())
            traitList.setVisibility(ListView.VISIBLE);

        // Determines whether brapi dialog should be shown on first click of a non-BrAPI
        // trait when a BrAPI field is selected.
        brapiDialogShown = false;
        mAdapter = new TraitAdapter(thisActivity, R.layout.listitem_trait, ConfigActivity.dt.getAllTraitObjects(), traitListener, visibility, brapiDialogShown);

        traitList.setAdapter(mAdapter);
        traitList.setDropListener(onDrop);
        traitList.setRemoveListener(onRemove);

        DragSortController controller = new DragSortController(traitList);
        controller.setDragHandleId(R.id.dragSort);
        controller.setRemoveEnabled(false);
        controller.setSortEnabled(true);
        controller.setDragInitMode(1);

        traitList.setFloatViewManager(controller);
        traitList.setOnTouchListener(controller);
        traitList.setDragEnabled(true);

        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_new_trait, null);
        traitDialog = new NewTraitDialog(layout, this);

        traitListener = new AdapterView.OnItemClickListener() {

            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                // When a trait is selected, alter the layout of the edit dialog accordingly

                TraitObject o = mAdapter.getItem(position);
                traitDialog.setTraitObject(o);

                loadData();
                traitDialog.show(true);
            }
        };

        FloatingActionButton fab = findViewById(R.id.newTrait);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCreateTraitDialog();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        new MenuInflater(TraitEditorActivity.this).inflate(R.menu.menu_traits, menu);

        systemMenu = menu;
        systemMenu.findItem(R.id.help).setVisible(ep.getBoolean("Tips", false));

        return true;
    }

    private Rect traitsListItemLocation(int item, int adjust) {
        View v = traitList.getChildAt(item);
        final int[] location = new int[2];
        v.getLocationOnScreen(location);
        Rect droidTarget = new Rect(location[0], location[1], location[0] + v.getWidth()/adjust, location[1] + v.getHeight());
        return droidTarget;
    }

    private TapTarget traitsTapTargetRect(Rect item, String title, String desc) {
        return TapTarget.forBounds(item, title, desc)
                // All options below are optional
                .outerCircleColor(R.color.main_primaryDark)      // Specify a color for the outer circle
                .outerCircleAlpha(0.95f)            // Specify the alpha amount for the outer circle
                .targetCircleColor(R.color.black)   // Specify a color for the target circle
                .titleTextSize(30)                  // Specify the size (in sp) of the title text
                .descriptionTextSize(20)            // Specify the size (in sp) of the description text
                .descriptionTextColor(R.color.black)  // Specify the color of the description text
                .descriptionTypeface(Typeface.DEFAULT_BOLD)
                .textColor(R.color.black)            // Specify a color for both the title and description text
                .dimColor(R.color.black)            // If set, will dim behind the view with 30% opacity of the given color
                .drawShadow(true)                   // Whether to draw a drop shadow or not
                .cancelable(false)                  // Whether tapping outside the outer circle dismisses the view
                .tintTarget(true)                   // Whether to tint the target view's color
                .transparentTarget(true)           // Specify whether the target is transparent (displays the content underneath)
                .targetRadius(60);
    }

    private TapTarget traitsTapTargetMenu(int id, String title, String desc) {
        return TapTarget.forView(findViewById(id), title, desc)
                // All options below are optional
                .outerCircleColor(R.color.main_primaryDark)      // Specify a color for the outer circle
                .outerCircleAlpha(0.95f)            // Specify the alpha amount for the outer circle
                .targetCircleColor(R.color.black)   // Specify a color for the target circle
                .titleTextSize(30)                  // Specify the size (in sp) of the title text
                .descriptionTextSize(20)            // Specify the size (in sp) of the description text
                .descriptionTextColor(R.color.black)  // Specify the color of the description text
                .descriptionTypeface(Typeface.DEFAULT_BOLD)
                .textColor(R.color.black)            // Specify a color for both the title and description text
                .dimColor(R.color.black)            // If set, will dim behind the view with 30% opacity of the given color
                .drawShadow(true)                   // Whether to draw a drop shadow or not
                .cancelable(false)                  // Whether tapping outside the outer circle dismisses the view
                .tintTarget(true)                   // Whether to tint the target view's color
                .transparentTarget(true)           // Specify whether the target is transparent (displays the content underneath)
                .targetRadius(60);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.help:
                TapTargetSequence sequence = new TapTargetSequence(this)
                        .targets(traitsTapTargetMenu(R.id.addTrait, getString(R.string.tutorial_traits_add_title), getString(R.string.tutorial_traits_add_description))
                                //Todo add overflow menu action
                        );

                if (ConfigActivity.dt.getTraitColumnData("trait") != null) {
                    sequence.target(traitsTapTargetRect(traitsListItemLocation(0,4), getString(R.string.tutorial_traits_visibility_title), getString(R.string.tutorial_traits_visibility_description)));
                    sequence.target(traitsTapTargetRect(traitsListItemLocation(0,2), getString(R.string.tutorial_traits_format_title), getString(R.string.tutorial_traits_format_description)));
                }

                sequence.start();
                break;

            case R.id.deleteTrait:
                showDeleteTraitDialog();
                break;

            case R.id.sortTrait:
                sortDialog();
                break;

            case R.id.importexport:
                importExportDialog();
                break;

            case R.id.addTrait:
                showCreateTraitDialog();
                break;

            case R.id.toggleTrait:
                changeAllVisibility();
                break;

            case android.R.id.home:
                MainActivity.reloadData = true;
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void changeAllVisibility() {
        boolean globalVis = ep.getBoolean("allTraitsVisible", false);
        String[] allTraits = ConfigActivity.dt.getTraitColumnData("trait");

        if (allTraits == null) {
            makeToast(getString(R.string.warning_traits_missing_modify));
            return;
        }

        for (String allTrait : allTraits) {
            ConfigActivity.dt.updateTraitVisibility(allTrait, globalVis);
            Log.d("Field", allTrait);
        }

        globalVis = !globalVis;

        Editor ed = ep.edit();
        ed.putBoolean("allTraitsVisible", globalVis);
        ed.apply();
        loadData();
    }

    private void importExportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialog);

        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_list, null);

        builder.setTitle(R.string.settings_traits)
                .setCancelable(true)
                .setView(layout);

        final AlertDialog importExport = builder.create();


        android.view.WindowManager.LayoutParams params = importExport.getWindow().getAttributes();
        params.width = LayoutParams.WRAP_CONTENT;
        params.height = LayoutParams.WRAP_CONTENT;
        importExport.getWindow().setAttributes(params);

        ListView myList = layout.findViewById(R.id.myList);

        String[] sortOptions = new String[2];

        sortOptions[0] = getString(R.string.dialog_import);
        sortOptions[1] = getString(R.string.traits_dialog_export);

        myList.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View arg1, int which, long arg3) {
                switch (which) {
                    case 0:
                        loadTraitFilePermission();
                        break;
                    case 1:
                        exportTraitFilePermission();
                        break;
                }
                importExport.dismiss();
            }
        });

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.listitem, sortOptions);
        myList.setAdapter(adapter);
        Button sortCloseBtn = layout.findViewById(R.id.closeBtn);

        sortCloseBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View arg0) {
                importExport.dismiss();
            }
        });
        importExport.show();
    }

    @AfterPermissionGranted(PERMISSIONS_REQUEST_STORAGE_IMPORT)
    public void loadTraitFilePermission() {
        String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE};
        if (EasyPermissions.hasPermissions(this, perms)) {
            if (ep.getBoolean("TraitsExported", false)) {
                showFileDialog();
            } else {
                checkTraitExportDialog();
            }
        } else {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(this, getString(R.string.permission_rationale_storage_import),
                    PERMISSIONS_REQUEST_STORAGE_IMPORT, perms);
        }

    }

    @AfterPermissionGranted(PERMISSIONS_REQUEST_STORAGE_EXPORT)
    public void exportTraitFilePermission() {
        String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE};
        if (EasyPermissions.hasPermissions(this, perms)) {
            showExportDialog();
        } else {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(this, getString(R.string.permission_rationale_storage_export),
                    PERMISSIONS_REQUEST_STORAGE_EXPORT, perms);
        }
    }

    private void showFileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialog);

        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_list, null);

        builder.setTitle(R.string.import_dialog_title_traits)
                .setCancelable(true)
                .setView(layout);

        final AlertDialog importDialog = builder.create();

        android.view.WindowManager.LayoutParams params = importDialog.getWindow().getAttributes();
        params.width = LayoutParams.MATCH_PARENT;
        params.height = LayoutParams.WRAP_CONTENT;
        importDialog.getWindow().setAttributes(params);

        ListView myList = layout.findViewById(R.id.myList);

        String[] importArray = new String[3];
        importArray[0] = getString(R.string.import_source_local);
        importArray[1] = getString(R.string.import_source_cloud);
        importArray[2] = getString(R.string.import_source_brapi);

        myList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View arg1, int which, long arg3) {
                Intent intent = new Intent();
                switch (which) {
                    case 0:
                        intent.setClassName(thisActivity,
                                FileExploreActivity.class.getName());
                        intent.putExtra("path", Constants.TRAITPATH);
                        intent.putExtra("include", new String[]{"trt"});
                        intent.putExtra("title", getString(R.string.traits_dialog_import));
                        startActivityForResult(intent, 1);
                        break;
                    case 1:
                        loadCloud();
                        break;
                    case 2:
                        intent.setClassName(thisActivity, BrapiTraitActivity.class.getName());
                        startActivityForResult(intent, 1);
                        break;
                }
                importDialog.dismiss();
            }
        });

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.listitem, importArray);
        myList.setAdapter(adapter);
        Button importCloseBtn = layout.findViewById(R.id.closeBtn);
        importCloseBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View arg0) {
                importDialog.dismiss();
            }
        });
        importDialog.show();
    }

    private void loadCloud() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");;
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(Intent.createChooser(intent, "cloudFile"), 5);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(getApplicationContext(), "No suitable File Manager was found.", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkTraitExportDialog() {
        String[] allTraits = ConfigActivity.dt.getTraitColumnData("trait");

        if (allTraits == null) {
            showFileDialog();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(TraitEditorActivity.this, R.style.AppAlertDialog);
        builder.setMessage(getString(R.string.traits_export_check));

        builder.setPositiveButton(getString(R.string.dialog_yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

                File file = new File(Constants.TRAITPATH);
                if (!file.exists()) {
                    createDir(Constants.FIELDEXPORTPATH);
                }

                showExportDialog();
                dialog.dismiss();
            }

        });

        builder.setNegativeButton(getString(R.string.dialog_no), new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                showFileDialog();
                dialog.dismiss();
            }

        });

        AlertDialog alert = builder.create();
        alert.show();
    }

    private void createDir(String path) {
        File dir = new File(path);
        File blankFile = new File(path + "/.fieldbook");

        if (!dir.exists()) {
            dir.mkdirs();

            try {
                blankFile.getParentFile().mkdirs();
                blankFile.createNewFile();
                Utils.scanFile(TraitEditorActivity.this, blankFile);
            } catch (IOException ignore) {
            }
        }
    }

    private void sortDialog() {
        String[] allTraits = ConfigActivity.dt.getTraitColumnData("trait");

        if (allTraits == null) {
            makeToast(getString(R.string.warning_traits_missing_modify));
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialog);

        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_list, null);

        builder.setTitle(R.string.traits_sort_title)
                .setCancelable(true)
                .setView(layout);

        final AlertDialog sortDialog = builder.create();

        android.view.WindowManager.LayoutParams params = sortDialog.getWindow().getAttributes();
        params.width = LayoutParams.WRAP_CONTENT;
        params.height = LayoutParams.WRAP_CONTENT;
        sortDialog.getWindow().setAttributes(params);

        ListView myList = layout.findViewById(R.id.myList);

        String[] sortOptions = new String[3];

        sortOptions[0] = getString(R.string.traits_sort_name);
        sortOptions[1] = getString(R.string.traits_sort_format);
        sortOptions[2] = getString(R.string.traits_sort_visibility);

        myList.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View arg1, int which, long arg3) {
                switch (which) {
                    case 0:
                        sortTraitList("trait");
                        break;
                    case 1:
                        sortTraitList("format");
                        break;
                    case 2:
                        sortTraitList("isVisible");
                        break;
                }
                sortDialog.dismiss();
            }
        });

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.listitem, sortOptions);
        myList.setAdapter(adapter);
        Button sortCloseBtn = layout.findViewById(R.id.closeBtn);

        sortCloseBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View arg0) {
                sortDialog.dismiss();
            }
        });
        sortDialog.show();
    }

    private void sortTraitList(String colName) {
        String[] sortList = ConfigActivity.dt.getTraitColumnData(colName);

        ArrayIndexComparator comparator = new ArrayIndexComparator(sortList);
        Integer[] indexes = comparator.createIndexArray();
        Arrays.sort(indexes, comparator);

        if (colName.equals("isVisible")) {
            Arrays.sort(indexes, Collections.reverseOrder());
        }

        for (int j = 0; j < indexes.length; j++) {
            ConfigActivity.dt.writeNewPosition(colName, sortList[j], Integer.toString(indexes[j]));
            Log.e("TRAIT", sortList[j] + " " + indexes[j].toString());
        }

        loadData();
    }

    private void showExportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialog);

        LayoutInflater inflater = this.getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_save_database, null);

        builder.setTitle(R.string.traits_dialog_export)
                .setCancelable(true)
                .setView(layout);

        final AlertDialog exportDialog = builder.create();

        android.view.WindowManager.LayoutParams langParams = exportDialog.getWindow().getAttributes();
        langParams.width = LayoutParams.MATCH_PARENT;
        exportDialog.getWindow().setAttributes(langParams);

        Button closeBtn = layout.findViewById(R.id.closeBtn);

        closeBtn.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                exportDialog.dismiss();
            }
        });

        final EditText exportFile = layout.findViewById(R.id.fileName);

        // As the export filename uses the import file name as well,
        // we parse it out here
        SimpleDateFormat timeStamp = new SimpleDateFormat(
                "yyyy-MM-dd-hh-mm-ss", Locale.getDefault());

        String exportName = "trait_export_"
                + timeStamp.format(Calendar.getInstance().getTime())
                + ".trt";

        exportFile.setText(exportName);

        Button exportButton = layout.findViewById(R.id.saveBtn);

        exportButton.setOnClickListener(new OnClickListener() {

            public void onClick(View arg0) {
                exportTable(exportFile.getText().toString());
                Editor ed = ep.edit();
                ed.putBoolean("TraitsExported", true);
                ed.apply();
                exportDialog.dismiss();
            }
        });

        exportDialog.show();
    }

    private void showDeleteTraitDialog() {
        String[] allTraits = ConfigActivity.dt.getTraitColumnData("trait");

        if (allTraits == null) {
            makeToast(getString(R.string.warning_traits_missing_modify));
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(TraitEditorActivity.this, R.style.AppAlertDialog);

        builder.setTitle(getString(R.string.traits_toolbar_delete_all));
        builder.setMessage(getString(R.string.dialog_confirm));

        builder.setPositiveButton(getString(R.string.dialog_yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                ConfigActivity.dt.deleteTable(DataHelper.TRAITS);
                loadData();
                dialog.dismiss();
            }

        });

        builder.setNegativeButton(getString(R.string.dialog_no), new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }

        });

        AlertDialog alert = builder.create();
        alert.show();
    }

    private void showCreateTraitDialog() {
        traitDialog.initTrait();
        traitDialog.show(false);
        traitDialog.prepareFields(0);
    }

    public void onBackPressed() {
        MainActivity.reloadData = true;
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                mChosenFile = data.getStringExtra("result");
                mHandler.post(importCSV);

                brapiDialogShown = mAdapter.infoDialogShown;
                if (!brapiDialogShown) {
                    brapiDialogShown = displayBrapiInfo(TraitEditorActivity.this, ConfigActivity.dt, null, true);
                }
            }
        }

        if (requestCode == 5 && resultCode == RESULT_OK && data.getData() != null) {
            Uri content_describer = data.getData();
            InputStream in = null;
            OutputStream out = null;
            try {
                in = getContentResolver().openInputStream(content_describer);
                out = new FileOutputStream(new File(Constants.FIELDIMPORTPATH + "/" + getFileName(content_describer)));
                byte[] buffer = new byte[1024];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (out != null){
                    try {
                        out.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            final String chosenFile = Constants.TRAITPATH + "/" + getFileName(content_describer);

            String extension = "";
            int i = chosenFile.lastIndexOf('.');
            if (i > 0) {
                extension = chosenFile.substring(i+1);
            }

            if(!extension.equals("trt")) {
                //TODO add to strings
                makeToast("Only TRT files can be loaded into Field Book.");
                Toast.makeText(TraitEditorActivity.thisActivity, getString(R.string.import_error_format_trait), Toast.LENGTH_LONG).show();

                return;
            }

            mChosenFile = chosenFile;
            mHandler.post(importCSV);
        }
    }

    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    // Helper function export data as CSV
    private void exportTable(String exportName) {
        File backup = new File(Constants.TRAITPATH);
        backup.mkdirs();

        File file = new File(Constants.TRAITPATH + "/" + exportName);

        try {
            FileWriter fw = new FileWriter(file);
            CSVWriter csvWriter = new CSVWriter(fw, ConfigActivity.dt.getAllTraitsForExport());
            csvWriter.writeTraitFile(ConfigActivity.dt.getTraitColumns());

            csvWriter.close();
        } catch (Exception ignore) {
        }

        shareFile(file);
    }

    public void makeToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Scan file to update file list and share exported file
     */
    private void shareFile(File filePath) {
        MediaScannerConnection.scanFile(this, new String[]{filePath.getAbsolutePath()}, null, null);

        if (!ep.getBoolean(PreferencesActivity.DISABLE_SHARE, false)) {
            Intent intent = new Intent();
            intent.setAction(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, this.getApplicationContext().getPackageName() + ".fileprovider", filePath));
            startActivity(Intent.createChooser(intent, "Sending File..."));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    private class ArrayIndexComparator implements Comparator<Integer> {
        private final String[] array;

        ArrayIndexComparator(String[] array) {
            this.array = array;
        }

        Integer[] createIndexArray() {
            Arrays.sort(array);
            Integer[] indexes = new Integer[array.length];
            for (int i = 0; i < array.length; i++) {
                indexes[i] = i;
            }
            return indexes;
        }

        @Override
        public int compare(Integer index1, Integer index2) {
            return array[index1].compareTo(array[index2]);
        }
    }

    private class ImportCSVTask extends AsyncTask<Integer, Integer, Integer> {
        ProgressDialog dialog;

        boolean fail;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            dialog = new ProgressDialog(thisActivity);
            dialog.setIndeterminate(true);
            dialog.setCancelable(false);
            dialog.setMessage(Html
                    .fromHtml(thisActivity.getString(R.string.import_dialog_importing)));
            dialog.show();
        }

        @Override
        protected Integer doInBackground(Integer... params) {
            try {
                String[] data;
                String[] columns;

                FileReader fr = new FileReader(mChosenFile);

                CSVReader cr = new CSVReader(fr);

                columns = cr.readNext();

                data = columns;

                if (ConfigActivity.dt.isTableExists(DataHelper.TRAITS)) {
                    ConfigActivity.dt.deleteTable(DataHelper.TRAITS);
                }

                while (data != null) {
                    data = cr.readNext();

                    if (data != null) {
                        TraitObject t = new TraitObject();
                        t.setTrait(data[0]);
                        t.setFormat(data[1]);
                        t.setDefaultValue(data[2]);
                        t.setMinimum(data[3]);
                        t.setMaximum(data[4]);
                        t.setDetails(data[5]);
                        t.setCategories(data[6]);
                        //t.visible = data[7].toLowerCase();
                        t.setRealPosition(data[8]);
                        if (data[7].toLowerCase().equals("true")) {
                            t.setVisible(true);
                        } else {
                            t.setVisible(false);
                        }
                        ConfigActivity.dt.insertTraits(t);
                    }
                }

                try {
                    cr.close();
                } catch (Exception ignore) {
                }

                try {
                    fr.close();
                } catch (Exception ignore) {
                }

                ConfigActivity.dt.close();
                ConfigActivity.dt.open();

                File newDir = new File(mChosenFile);

                newDir.mkdirs();

            } catch (Exception e) {
                e.printStackTrace();
                fail = true;
            }

            return 0;
        }

        @Override
        protected void onPostExecute(Integer result) {
            Editor ed = ep.edit();
            ed.putBoolean("CreateTraitFinished", true);
            ed.apply();

            loadData();

            MainActivity.reloadData = true;

            if (dialog.isShowing())
                dialog.dismiss();

            if (fail)
                makeToast(thisActivity.getString(R.string.import_error_general));
        }
    }
}