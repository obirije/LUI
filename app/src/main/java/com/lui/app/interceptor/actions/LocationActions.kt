package com.lui.app.interceptor.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale

object LocationActions {

    fun getLocation(context: Context): ActionResult {
        if (!hasLocationPermission(context)) {
            return ActionResult.Failure("I need location permission. Grant it in Settings > Apps > LUI > Permissions.")
        }

        val location = getLastKnownLocation(context)
            ?: return ActionResult.Failure("Couldn't get your location. Make sure location services are enabled.")

        val address = reverseGeocode(context, location.latitude, location.longitude)
        return if (address != null) {
            ActionResult.Success("You're at $address (${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)}).")
        } else {
            ActionResult.Success("You're at ${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)}.")
        }
    }

    fun getDistance(context: Context, destination: String): ActionResult {
        if (!hasLocationPermission(context)) {
            return ActionResult.Failure("I need location permission to calculate distance.")
        }

        val currentLocation = getLastKnownLocation(context)
            ?: return ActionResult.Failure("Couldn't get your location. Make sure location services are enabled.")

        // Geocode destination
        val destLocation = geocodeAddress(context, destination)
            ?: return ActionResult.Failure("Couldn't find \"$destination\". Try a more specific address.")

        val results = FloatArray(1)
        Location.distanceBetween(
            currentLocation.latitude, currentLocation.longitude,
            destLocation.first, destLocation.second,
            results
        )
        val meters = results[0]
        val display = if (meters > 1000) {
            String.format("%.1f km", meters / 1000)
        } else {
            "${meters.toInt()} meters"
        }

        val destAddress = reverseGeocode(context, destLocation.first, destLocation.second)
        val destName = destAddress ?: destination

        // Estimate drive time: ~40 km/h city, ~80 km/h highway, use 50 km/h average
        // Straight-line distance × 1.3 to approximate road distance
        val roadDistKm = (meters * 1.3) / 1000
        val driveMinutes = (roadDistKm / 50.0 * 60).toInt()
        val driveTime = when {
            driveMinutes < 2 -> "about a minute"
            driveMinutes < 60 -> "about $driveMinutes minutes"
            else -> {
                val hours = driveMinutes / 60
                val mins = driveMinutes % 60
                if (mins == 0) "about ${hours}h" else "about ${hours}h ${mins}m"
            }
        }

        return ActionResult.Success("$destName is about $display away. Estimated drive time: $driveTime.")
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun getLastKnownLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            // Try GPS first, then network
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
        } catch (e: SecurityException) { null }
    }

    /** Returns Pair(lat, lng) or null */
    private fun geocodeAddress(context: Context, address: String): Pair<Double, Double>? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(address, 1)
            if (!results.isNullOrEmpty()) {
                Pair(results[0].latitude, results[0].longitude)
            } else null
        } catch (e: Exception) { null }
    }

    private fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lng, 1)
            if (!results.isNullOrEmpty()) {
                val addr = results[0]
                // Build a readable address
                val parts = mutableListOf<String>()
                addr.thoroughfare?.let { parts.add(it) }  // street
                addr.subThoroughfare?.let { parts.add(0, it) }  // number
                addr.locality?.let { parts.add(it) }  // city
                if (parts.isEmpty()) addr.getAddressLine(0) else parts.joinToString(", ")
            } else null
        } catch (e: Exception) { null }
    }
}
