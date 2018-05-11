package me.impy.aegis.ui;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.support.v7.app.AlertDialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import me.impy.aegis.AegisApplication;
import me.impy.aegis.R;
import me.impy.aegis.crypto.MasterKey;
import me.impy.aegis.db.DatabaseEntry;
import me.impy.aegis.db.DatabaseManager;
import me.impy.aegis.db.DatabaseManagerException;
import me.impy.aegis.db.slots.SlotCollection;
import me.impy.aegis.helpers.PermissionHelper;
import me.impy.aegis.importers.AegisImporter;
import me.impy.aegis.importers.DatabaseImporter;
import me.impy.aegis.importers.DatabaseImporterException;
import me.impy.aegis.util.ByteInputStream;

public class PreferencesFragment extends PreferenceFragment {
    // activity request codes
    private static final int CODE_IMPORT = 0;
    private static final int CODE_IMPORT_DECRYPT = 1;
    private static final int CODE_SLOTS = 2;

    // permission request codes
    private static final int CODE_PERM_IMPORT = 0;
    private static final int CODE_PERM_EXPORT = 1;

    private Intent _result = new Intent();
    private DatabaseManager _db;

    // this is used to keep a reference to a database converter
    // while the user provides credentials to decrypt it
    private DatabaseImporter _converter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        AegisApplication app = (AegisApplication) getActivity().getApplication();
        _db = app.getDatabaseManager();

        // set the result intent in advance
        getActivity().setResult(Activity.RESULT_OK, _result);

        Preference darkModePreference = findPreference("pref_dark_mode");
        darkModePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                _result.putExtra("needsRecreate", true);
                Toast.makeText(getActivity(), "Dark mode setting will be applied after closing this screen", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        Preference exportPreference = findPreference("pref_import");
        exportPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                onImport();
                return true;
            }
        });

        Preference importPreference = findPreference("pref_export");
        importPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                onExport();
                return true;
            }
        });

        Preference slotsPreference = findPreference("pref_slots");
        slotsPreference.setEnabled(_db.getFile().isEncrypted());
        slotsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                MasterKey masterKey = _db.getMasterKey();
                Intent intent = new Intent(getActivity(), SlotManagerActivity.class);
                intent.putExtra("masterKey", masterKey);
                intent.putExtra("slots", _db.getFile().getSlots());
                startActivityForResult(intent, CODE_SLOTS);
                return true;
            }
        });

        EditTextPreference timeoutPreference = (EditTextPreference) findPreference("pref_timeout");
        timeoutPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                preference.setSummary(String.format(getString(R.string.pref_timeout_summary), (String) newValue));
                return true;
            }
        });
        timeoutPreference.getOnPreferenceChangeListener().onPreferenceChange(timeoutPreference, timeoutPreference.getText());

        Preference issuerPreference = findPreference("pref_issuer");
        issuerPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                _result.putExtra("needsRefresh", true);
                return true;
            }
        });

        Preference screenPreference = findPreference("pref_secure_screen");
        screenPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                _result.putExtra("needsRecreate", true);
                Window window = getActivity().getWindow();
                if ((boolean)newValue) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
                return true;
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!PermissionHelper.checkResults(grantResults)) {
            Toast.makeText(getActivity(), "Permission denied", Toast.LENGTH_SHORT).show();
            return;
        }

        switch (requestCode) {
            case CODE_PERM_IMPORT:
                onImport();
                break;
            case CODE_PERM_EXPORT:
                onExport();
                break;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null) {
            return;
        }

        switch (requestCode) {
            case CODE_IMPORT:
                onImportResult(resultCode, data);
                break;
            case CODE_IMPORT_DECRYPT:
                onImportDecryptResult(resultCode, data);
                break;
            case CODE_SLOTS:
                onSlotManagerResult(resultCode, data);
                break;
        }
    }

    private void onImport() {
        if (PermissionHelper.request(getActivity(), CODE_PERM_IMPORT, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, CODE_IMPORT);
        }
    }

    private void onImportDecryptResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            _converter = null;
            return;
        }

        MasterKey key = (MasterKey) data.getSerializableExtra("key");
        ((AegisImporter)_converter).setKey(key);

        try {
            importDatabase(_converter);
        } catch (DatabaseImporterException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), "An error occurred while trying to parse the file", Toast.LENGTH_SHORT).show();
        }

        _converter = null;
    }

    private void onImportResult(int resultCode, Intent data) {
        Uri uri = data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null) {
            return;
        }

        ByteInputStream stream;
        InputStream fileStream = null;

        try {
            fileStream = getActivity().getContentResolver().openInputStream(uri);
            stream = ByteInputStream.create(fileStream);
        } catch (FileNotFoundException e) {
            Toast.makeText(getActivity(), "Error: File not found", Toast.LENGTH_SHORT).show();
            return;
        } catch (IOException e) {
            Toast.makeText(getActivity(), "An error occurred while trying to read the file", Toast.LENGTH_SHORT).show();
            return;
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        boolean imported = false;
        for (DatabaseImporter converter : DatabaseImporter.create(stream)) {
            try {
                converter.parse();

                // special case to decrypt encrypted aegis databases
                if (converter.isEncrypted() && converter instanceof AegisImporter) {
                    _converter = converter;

                    Intent intent = new Intent(getActivity(), AuthActivity.class);
                    intent.putExtra("slots", ((AegisImporter)_converter).getFile().getSlots());
                    startActivityForResult(intent, CODE_IMPORT_DECRYPT);
                    return;
                }

                importDatabase(converter);
                imported = true;
                break;
            } catch (DatabaseImporterException e) {
                e.printStackTrace();
                stream.reset();
            }
        }

        if (!imported) {
            Toast.makeText(getActivity(), "An error occurred while trying to parse the file", Toast.LENGTH_SHORT).show();
        }
    }

    private void importDatabase(DatabaseImporter converter) throws DatabaseImporterException {
        List<DatabaseEntry> entries = converter.convert();
        for (DatabaseEntry entry : entries) {
            _db.addKey(entry);
        }

        if (!saveDatabase()) {
            return;
        }

        _result.putExtra("needsRecreate", true);
        Toast.makeText(getActivity(), String.format(Locale.getDefault(), "Imported %d entries", entries.size()), Toast.LENGTH_LONG).show();
    }

    private void onExport() {
        if (!PermissionHelper.request(getActivity(), CODE_PERM_EXPORT, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return;
        }

        // TODO: create a custom layout to show a message AND a checkbox
        final boolean[] checked = {true};
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                .setTitle("Export the database")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String filename;
                    try {
                        filename = _db.export(checked[0]);
                    } catch (DatabaseManagerException e) {
                        e.printStackTrace();
                        Toast.makeText(getActivity(), "An error occurred while trying to export the database", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // make sure the new file is visible
                    MediaScannerConnection.scanFile(getActivity(), new String[]{filename}, null, null);

                    Toast.makeText(getActivity(), "The database has been exported to: " + filename, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (_db.getFile().isEncrypted()) {
            final String[] items = {"Keep the database encrypted"};
            final boolean[] checkedItems = {true};
            builder.setMultiChoiceItems(items, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int index, boolean isChecked) {
                    checked[0] = isChecked;
                }
            });
        } else {
            builder.setMessage("This action will export the database out of Android's private storage.");
        }
        builder.show();
    }

    private void onSlotManagerResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        SlotCollection slots = (SlotCollection) data.getSerializableExtra("slots");
        _db.getFile().setSlots(slots);
        saveDatabase();
    }

    private boolean saveDatabase() {
        try {
            _db.save();
        } catch (DatabaseManagerException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(), "An error occurred while trying to save the database", Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
    }
}