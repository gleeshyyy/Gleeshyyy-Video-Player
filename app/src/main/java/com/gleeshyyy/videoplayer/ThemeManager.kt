package com.gleeshyyy.videoplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.appcompat.app.AppCompatDelegate

data class PlayerPalette(val background:Int,val surface:Int,val raised:Int,val text:Int,val muted:Int,val accent:Int,val brandText:Int,val isDark:Boolean)

object ThemeManager {
    fun applySystemMode(context:Context) {
        val mode=context.getSharedPreferences("gleeshplayer_settings",Context.MODE_PRIVATE).getString("theme_mode","dark")
        AppCompatDelegate.setDefaultNightMode(when(mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "auto" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        })
    }
    fun palette(context:Context):PlayerPalette {
        val p=context.getSharedPreferences("gleeshplayer_settings",Context.MODE_PRIVATE)
        val mode=p.getString("theme_mode","dark") ?: "dark"
        val dark=when(mode) {
            "light" -> false
            "auto" -> (context.resources.configuration.uiMode and 0x30) != 0x10
            "picture" -> pictureIsDark(context,p.getString("theme_picture",null))
            else -> true
        }
        val accent=if(mode=="picture") pictureAccent(context,p.getString("theme_picture",null)) else Color.rgb(32,214,107)
        return if(dark) PlayerPalette(Color.rgb(8,13,18),Color.rgb(17,26,34),Color.rgb(31,43,53),Color.rgb(241,245,247),Color.rgb(153,166,175),accent,Color.WHITE,true)
        else PlayerPalette(Color.rgb(246,248,249),Color.WHITE,Color.rgb(231,236,239),Color.rgb(25,32,38),Color.rgb(91,103,112),accent,Color.rgb(18,27,34),false)
    }
    fun loadPicture(context:Context,uri:String?):Bitmap?=try {
        if(uri.isNullOrBlank()) null else context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    } catch(_:Exception){null}
    fun pictureIsDark(context:Context,uri:String?):Boolean {
        val bitmap=loadPicture(context,uri) ?: return true
        val sample=Bitmap.createScaledBitmap(bitmap,1,1,true)
        val c=sample.getPixel(0,0)
        if(sample!==bitmap) sample.recycle()
        bitmap.recycle()
        return (Color.red(c)*.299+Color.green(c)*.587+Color.blue(c)*.114)<145
    }
    fun pictureAccent(context:Context,uri:String?):Int {
        val bitmap=loadPicture(context,uri) ?: return Color.rgb(32,214,107)
        val sample=Bitmap.createScaledBitmap(bitmap,1,1,true)
        val c=sample.getPixel(0,0)
        if(sample!==bitmap) sample.recycle()
        bitmap.recycle()
        return Color.rgb((Color.red(c)*1.2f).toInt().coerceAtMost(255),(Color.green(c)*1.2f).toInt().coerceAtMost(255),(Color.blue(c)*1.2f).toInt().coerceAtMost(255))
    }
    fun blur(view:View,radius:Float) {
        if(Build.VERSION.SDK_INT>=31) view.setRenderEffect(if(radius>0f) RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP) else null)
    }
}
