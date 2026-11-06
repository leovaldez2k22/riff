package com.musicglass.desktop

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Rectangle
import java.awt.Window

/** Subclasses only this app's frame and its rendering children; restores every surviving procedure on close. */
internal class WindowsChrome(private val window: Window) : AutoCloseable {
    private interface Api : StdCallLibrary {
        fun GetWindowLongPtrW(hwnd:Pointer,index:Int):Pointer?
        fun SetWindowLongPtrW(hwnd:Pointer,index:Int,value:Pointer?):Pointer?
        fun SetWindowLongPtrW(hwnd:Pointer,index:Int,value:Proc):Pointer?
        fun CallWindowProcW(proc:Pointer?,hwnd:Pointer,msg:Int,wp:Pointer?,lp:Pointer?):Pointer?
        fun DefWindowProcW(hwnd:Pointer,msg:Int,wp:Pointer?,lp:Pointer?):Pointer?
        fun GetWindowRect(hwnd:Pointer,rect:Rect):Boolean
        fun EnumChildWindows(hwnd:Pointer,proc:EnumProc,data:Pointer?):Boolean
        fun IsChild(parent:Pointer,child:Pointer):Boolean
        fun SetWindowPos(hwnd:Pointer,after:Pointer?,x:Int,y:Int,cx:Int,cy:Int,flags:Int):Boolean
        fun IsZoomed(hwnd:Pointer):Boolean
    }
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd:Pointer,attribute:Int,value:IntByReference,size:Int):Int
    }
    interface Proc : StdCallLibrary.StdCallCallback {
        fun invoke(hwnd:Pointer,msg:Int,wp:Pointer?,lp:Pointer?):Pointer?
    }
    interface EnumProc : StdCallLibrary.StdCallCallback {
        fun invoke(hwnd:Pointer,data:Pointer?):Boolean
    }
    @Structure.FieldOrder("left","top","right","bottom")
    class Rect : Structure() {
        @JvmField var left=0; @JvmField var top=0; @JvmField var right=0; @JvmField var bottom=0
    }
    private data class ChildHook(val previous:Pointer?,val proc:Proc)
    private val api=Native.load("user32",Api::class.java)
    private val hwnd=Native.getWindowPointer(window)
    private val oldStyle=api.GetWindowLongPtrW(hwnd,-16)
    private val previous=api.GetWindowLongPtrW(hwnd,-4)
    private val children=linkedMapOf<Pointer,ChildHook>()
    private var closed=false
    @Volatile var captionBounds=Rectangle()
    @Volatile var maximizeBounds=Rectangle()

    private fun region(lp:Pointer?):Int {
        val rect=Rect()
        if(!api.GetWindowRect(hwnd,rect)) return 0
        val packed=Pointer.nativeValue(lp)
        val x=(packed and 0xffff).toShort().toInt()-rect.left
        val y=((packed ushr 16) and 0xffff).toShort().toInt()-rect.top
        val edge=(8*window.graphicsConfiguration.defaultTransform.scaleX).toInt().coerceAtLeast(4)
        return chromeHitTest(x,y,rect.right-rect.left,rect.bottom-rect.top,edge,
            api.IsZoomed(hwnd),captionBounds,maximizeBounds)
    }
    private val proc=object : Proc {
        override fun invoke(hwnd:Pointer,msg:Int,wp:Pointer?,lp:Pointer?):Pointer? {
            val hit=Pointer.nativeValue(wp).toInt()
            if(msg==0x210 && hit and 0xffff==1) javax.swing.SwingUtilities.invokeLater { refreshChildren() }
            // Undecorated AWT peers suppress these non-client actions; USER32 owns their native move/resize loop.
            if((msg==0xA1 && (hit==2 || hit==9 || hit in 10..17)) || (msg==0xA3 && hit==2) ||
                (msg==0xA5 && hit==2) || (msg==0xA0 && hit==9) || (msg==0xA2 && hit==9))
                return api.DefWindowProcW(hwnd,msg,wp,lp)
            if(msg==0x83 && Pointer.nativeValue(wp)!=0L) return Pointer.NULL
            if(msg==0x84) { val r=region(lp); if(r!=0) return Pointer.createConstant(r) }
            return api.CallWindowProcW(previous,hwnd,msg,wp,lp)
        }
    }
    init {
        check(Native.POINTER_SIZE==8) { "The preview requires 64-bit Windows" }
        api.SetWindowLongPtrW(hwnd,-16,Pointer.createConstant(Pointer.nativeValue(oldStyle) or 0x00CF0000L))
        api.SetWindowLongPtrW(hwnd,-4,proc)
        runCatching { Native.load("dwmapi",Dwm::class.java).DwmSetWindowAttribute(hwnd,33,IntByReference(2),4) }
        api.SetWindowPos(hwnd,null,0,0,0,0,0x0037)
        refreshChildren()
    }
    @Synchronized fun refreshChildren() {
        if(closed) return
        api.EnumChildWindows(hwnd,object : EnumProc {
            override fun invoke(child:Pointer,data:Pointer?):Boolean {
                if(child in children) return true
                val old=api.GetWindowLongPtrW(child,-4)
                val callback=object : Proc {
                    override fun invoke(hwnd:Pointer,msg:Int,wp:Pointer?,lp:Pointer?):Pointer? {
                        // Skia's heavyweight surface covers the client area. Let the frame receive non-client hits.
                        if(msg==0x84 && region(lp)!=0) return Pointer.createConstant(-1)
                        return api.CallWindowProcW(old,hwnd,msg,wp,lp)
                    }
                }
                api.SetWindowLongPtrW(child,-4,callback)
                children[child]=ChildHook(old,callback)
                return true
            }
        },null)
    }
    @Synchronized fun verifyInstalled():Boolean = Pointer.nativeValue(api.GetWindowLongPtrW(hwnd,-16)) and 0x40000L!=0L &&
        api.GetWindowLongPtrW(hwnd,-4)==CallbackReference.getFunctionPointer(proc) && children.all { (child,hook) ->
            !api.IsChild(hwnd,child) || api.GetWindowLongPtrW(child,-4)==CallbackReference.getFunctionPointer(hook.proc)
        }
    @Synchronized override fun close() {
        closed=true
        children.forEach { (child,hook) -> if(api.IsChild(hwnd,child)) api.SetWindowLongPtrW(child,-4,hook.previous) }
        children.clear()
        api.SetWindowLongPtrW(hwnd,-4,previous)
        api.SetWindowLongPtrW(hwnd,-16,oldStyle)
    }
}

/** Edge/corner resize takes precedence over caption pixels. Positions are window-local physical pixels. */
internal fun chromeHitTest(x:Int,y:Int,width:Int,height:Int,edge:Int,maximized:Boolean,
    caption:Rectangle,maximize:Rectangle):Int {
    if(!maximized) {
        val left=x<edge; val right=x>=width-edge; val top=y<edge; val bottom=y>=height-edge
        val region=when {top&&left->13;top&&right->14;bottom&&left->16;bottom&&right->17
            left->10;right->11;top->12;bottom->15;else->0}
        if(region!=0) return region
    }
    if(maximize.contains(x,y)) return 9
    if(caption.contains(x,y)) return 2
    return 0
}
