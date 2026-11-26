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
    @Volatile private var cachedUri: String? = null
    @Volatile private var cachedBitmap: Bitmap? = null

    fun applySystemMode(context:Context) {
        val mode=context.getSharedPreferences("gleeshplayer_settings",Context.MODE_PRIVATE).getString("theme_mode","dark")
        AppCompatDelegate.setDefaultNightMode(when(mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "auto" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        })
    }

    @Synchronized private fun decodeSmall(context:Context, uri:String?):Bitmap? {
        if(uri.isNullOrBlank()) return null
        if(cachedUri == uri && cachedBitmap?.isRecycled == false) return cachedBitmap
        return try {
            val resolver=context.contentResolver
            val parsed=Uri.parse(uri)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it,null,bounds) }
            var sample=1
            while(bounds.outWidth/sample>1600 || bounds.outHeight/sample>1600) sample*=2
            val options=BitmapFactory.Options().apply { inSampleSize=sample.coerceAtLeast(1); inPreferredConfig=Bitmap.Config.RGB_565 }
            val decoded=resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it,null,options) }
            cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
            cachedUri=uri
            cachedBitmap=decoded
            decoded
        } catch(_:Exception){null}
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

    fun loadPicture(context:Context,uri:String?):Bitmap?=decodeSmall(context,uri)

    fun pictureIsDark(context:Context,uri:String?):Boolean {
        val bitmap=decodeSmall(context,uri) ?: return true
        val c=averageColor(bitmap)
        return (Color.red(c)*.299+Color.green(c)*.587+Color.blue(c)*.114)<145
    }

    fun pictureAccent(context:Context,uri:String?):Int {
        val bitmap=decodeSmall(context,uri) ?: return Color.rgb(32,214,107)
        val c=averageColor(bitmap)
        val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
        val max=maxOf(r,g,b); val min=minOf(r,g,b)
        val saturation=if(max==0) 0f else (max-min).toFloat()/max
        return if(saturation<0.12f) Color.rgb(32,214,107) else Color.rgb((r*1.15f).toInt().coerceAtMost(255),(g*1.15f).toInt().coerceAtMost(255),(b*1.15f).toInt().coerceAtMost(255))
    }

    private fun averageColor(bitmap:Bitmap):Int {
        var red=0L; var green=0L; var blue=0L; var count=0L
        val step=maxOf(1,bitmap.width/40)
        var y=0
        while(y<bitmap.height) {
            var x=0
            while(x<bitmap.width) {
                val c=bitmap.getPixel(x,y)
                red+=Color.red(c); green+=Color.green(c); blue+=Color.blue(c); count++
                x+=step
            }
            y+=step
        }
        if(count==0L) return Color.DKGRAY
        return Color.rgb((red/count).toInt(),(green/count).toInt(),(blue/count).toInt())
    }

    fun blur(view:View,radius:Float) {
        if(Build.VERSION.SDK_INT>=31) view.setRenderEffect(if(radius>0f) RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP) else null)
    }
}