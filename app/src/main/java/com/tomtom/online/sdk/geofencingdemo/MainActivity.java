package com.tomtom.online.sdk.geofencingdemo;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.StrictMode;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.location.LocationRequest;
import com.tomtom.online.sdk.common.location.LatLng;
import com.tomtom.online.sdk.common.permission.AndroidPermissionChecker;
import com.tomtom.online.sdk.common.permission.PermissionChecker;
import com.tomtom.online.sdk.geofencing.GeofencingApi;
import com.tomtom.online.sdk.geofencing.ReportServiceResultListener;
import com.tomtom.online.sdk.geofencing.data.report.FenceDetails;
import com.tomtom.online.sdk.geofencing.data.report.ReportServiceQuery;
import com.tomtom.online.sdk.geofencing.data.report.ReportServiceQueryBuilder;
import com.tomtom.online.sdk.geofencing.data.report.ReportServiceResponse;
import com.tomtom.online.sdk.location.LocationSource;
import com.tomtom.online.sdk.location.LocationSourceFactory;
import com.tomtom.online.sdk.location.LocationUpdateListener;
import com.tomtom.online.sdk.map.MapFragment;
import com.tomtom.online.sdk.map.MarkerBuilder;
import com.tomtom.online.sdk.map.OnMapReadyCallback;
import com.tomtom.online.sdk.map.Polygon;
import com.tomtom.online.sdk.map.PolygonBuilder;
import com.tomtom.online.sdk.map.TomtomMap;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements LocationUpdateListener, OnMapReadyCallback {
    private static final int PERMISSION_REQUEST_LOCATION = 0;
    private static final int DISTANCE_IN_METERS = 10;
    private static String PROJECT_ID;
    private static String GEOFENCING_APIKEY;

    private LocationSource locationSource;
    private GeofencingApi geofencingApi;
    private TomtomMap tomtomMap;
    private Location previousLocation;
    private EditText editTextRange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setProjectApiKeys();

        //TODO remove StrictMode and change Volley, Retrofit or use AsyncTask...
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        initLocationSource();
        geofencingApi = GeofencingApi.create(this);
        MapFragment mapFragment = (MapFragment) getSupportFragmentManager().findFragmentById(R.id.mapFragment);
        mapFragment.getAsyncMap(this);

        editTextRange = findViewById(R.id.editText);
        Button buttonRefreshReport = findViewById(R.id.button_main_start);
        buttonRefreshReport.setOnClickListener(v -> {
            if (previousLocation != null) {
                refreshGeofencingRaport(previousLocation);
            }
        });
    }

    private void setProjectApiKeys() {
        try {
            ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(this.getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = applicationInfo.metaData;
            PROJECT_ID = bundle.getString("GeofencingProject.ID");
            GEOFENCING_APIKEY = bundle.getString("GeofencingApi.Key");
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onLocationChanged(Location currentLocation) {
        if (this.tomtomMap != null) {
            if (previousLocation == null) {
                previousLocation = currentLocation;
                refreshGeofencingRaport(currentLocation);
            } else if (getDistanceBetweenTwoLocationsInMeters(previousLocation, currentLocation) > DISTANCE_IN_METERS) {
                previousLocation = currentLocation;
                //refreshGeofencingRaport(currentLocation);
            }
        }
    }

    private float getDistanceBetweenTwoLocationsInMeters(Location previousLocation, Location location) {
        float[] distanceInMeters = new float[1];
        Location.distanceBetween(previousLocation.getLatitude(), previousLocation.getLongitude(), location.getLatitude(), location.getLongitude(), distanceInMeters);
        return distanceInMeters[0];
    }

    private void refreshGeofencingRaport(Location location) {
        tomtomMap.getOverlaySettings().removeOverlays();
        tomtomMap.removeMarkers();

        ReportServiceQuery query = ReportServiceQueryBuilder.create(location)
                .withProject(UUID.fromString(PROJECT_ID))
                .withRange(Float.valueOf(editTextRange.getText().toString()))
                .build();
        geofencingApi.obtainReport(query, new ReportServiceResultListener() {

            @Override
            public void onResponse(@NotNull ReportServiceResponse reportServiceResponse) {
                try {
                    for (FenceDetails fenceDetails : reportServiceResponse.getReport().getInside()) {
                        drawFenceOnMap(getFenceCoordinates(fenceDetails), Color.GREEN);
                    }
                    for (FenceDetails fenceDetails : reportServiceResponse.getReport().getOutside()) {
                        MainActivity.this.tomtomMap.addMarker(new MarkerBuilder(fenceDetails.getClosestPoint()));
                        drawFenceOnMap(getFenceCoordinates(fenceDetails), Color.RED);
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(@NotNull Throwable throwable) {
                Log.d("GEOFENCE", "onError: " + throwable.getLocalizedMessage());
            }
        });
    }

    private void drawFenceOnMap(List<LatLng> latLngList, int color) {
        Polygon polygon = PolygonBuilder.create()
                .coordinates(latLngList)
                .color(color)
                .outlineColor(Color.WHITE)
                .opacity(0.7f)
                .build();
        tomtomMap.getOverlaySettings().addOverlay(polygon);
    }

    private String getGeofencingURL(String fenceId) {
        String geofenceBaseUrl = "https://api.tomtom.com/geofencing/1/fences/";
        return geofenceBaseUrl + fenceId + "?key=" + GEOFENCING_APIKEY;
    }

    @NotNull
    private List<LatLng> getFenceCoordinates(FenceDetails fenceDetails) throws IOException, JSONException {
        List<LatLng> latLngList = new LinkedList<>();
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(getGeofencingURL(fenceDetails.getFence().getId().toString()))
                .build();
        Response responses = null;
        try {
            responses = client.newCall(request).execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String jsonData = responses.body().string();
        JSONObject jobject = new JSONObject(jsonData);
        JSONObject geometry = jobject.getJSONObject("geometry");
        JSONArray coordinates = geometry.getJSONArray("coordinates");
        JSONArray latlngs = coordinates.getJSONArray(0);
        for (int i = 0; i < latlngs.length(); i++) {
            latLngList.add(new LatLng(latlngs.getJSONArray(i).getDouble(1), latlngs.getJSONArray(i).getDouble(0)));
        }
        return latLngList;
    }

    @Override
    protected void onResume() {
        super.onResume();
        PermissionChecker checker = AndroidPermissionChecker.createLocationChecker(this);
        if (!checker.ifNotAllPermissionGranted()) {
            locationSource.activate();
        }
    }

    private void initLocationSource() {
        PermissionChecker permissionChecker = AndroidPermissionChecker.createLocationChecker(this);
        if (permissionChecker.ifNotAllPermissionGranted()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_LOCATION);
        }
        LocationSourceFactory locationSourceFactory = new LocationSourceFactory();
        locationSource = locationSourceFactory.createDefaultLocationSource(this, this, LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setFastestInterval(2000)
                .setInterval(5000));
    }

    @Override
    public void onMapReady(@NonNull TomtomMap tomtomMap) {
        this.tomtomMap = tomtomMap;
        this.tomtomMap.setMyLocationEnabled(true);
    }
}
