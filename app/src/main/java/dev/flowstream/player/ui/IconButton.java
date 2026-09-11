package dev.flowstream.player.ui;

import android.content.Context;
import android.graphics.*;
import android.widget.Button;

/** Resolution-independent line icons; text remains available for accessibility/state. */
public final class IconButton extends Button {
    private final String icon;
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    public IconButton(Context c,String icon,String description,Runnable action){
        super(c);this.icon=icon;setContentDescription(description);setTextColor(Ui.TEXT);setBackgroundColor(Color.TRANSPARENT);setStateListAnimator(null);
        setMinWidth(0);setMinimumWidth(0);setMinHeight(0);setMinimumHeight(0);setPadding(0,0,0,0);setOnClickListener(v->action.run());
    }
    private void line(Canvas c,float x,float y,float x2,float y2){c.drawLine(x,y,x2,y2,pen);}
    private void rect(Canvas c,float x,float y,float r,float b){c.drawRoundRect(x,y,r,b,2,2,pen);}
    @Override protected void onDraw(Canvas canvas){
        float size=Math.min(getWidth(),getHeight())*.52f;canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);
        pen.setColor(getCurrentTextColor());pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.8f);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
        switch(icon){
            case "close":line(canvas,5,5,19,19);line(canvas,19,5,5,19);break;
            case "back":line(canvas,15,4,7,12);line(canvas,7,12,15,20);break;
            case "more":canvas.drawCircle(12,12,9,pen);pen.setStyle(Paint.Style.FILL);for(int x:new int[]{7,12,17})canvas.drawCircle(x,12,1.2f,pen);break;
            case "pip":rect(canvas,2,3,19,17);rect(canvas,11,11,23,22);break;
            case "fit":canvas.drawCircle(12,12,10,pen);line(canvas,6,6,11,11);line(canvas,6,6,10,6);line(canvas,6,6,6,10);line(canvas,18,18,13,13);line(canvas,18,18,14,18);line(canvas,18,18,18,14);break;
            case "rotate":canvas.drawArc(3,3,21,21,210,145,false,pen);canvas.drawArc(3,3,21,21,30,145,false,pen);line(canvas,21,7,21,12);line(canvas,21,12,17,9);line(canvas,3,17,3,12);line(canvas,3,12,7,15);break;
            case "speed":canvas.drawCircle(12,12,10,pen);line(canvas,12,12,17,6);for(int i=0;i<5;i++){double a=Math.PI*(1+i*.25);float x=12+(float)Math.cos(a)*7,y=12+(float)Math.sin(a)*7;canvas.drawCircle(x,y,.45f,pen);}break;
            case "grid":rect(canvas,3,3,10,10);rect(canvas,14,3,21,10);rect(canvas,3,14,10,21);rect(canvas,14,14,21,21);break;
            case "files":rect(canvas,3,3,21,15);line(canvas,8,21,16,21);line(canvas,12,15,12,21);break;
            case "info":canvas.drawCircle(12,12,9,pen);line(canvas,12,11,12,18);line(canvas,9,18,15,18);canvas.drawCircle(12,7,.7f,pen);break;
            case "settings":canvas.drawCircle(12,12,6,pen);canvas.drawCircle(12,12,2,pen);for(int i=0;i<8;i++){double a=i*Math.PI/4;line(canvas,12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10);}break;
            case "random":line(canvas,3,6,7,6);line(canvas,7,6,17,18);line(canvas,17,18,21,18);line(canvas,3,18,7,18);line(canvas,7,18,17,6);line(canvas,17,6,21,6);line(canvas,18,3,21,6);line(canvas,18,9,21,6);line(canvas,18,15,21,18);line(canvas,18,21,21,18);break;
            case "lock":rect(canvas,4,10,20,22);canvas.drawArc(7,1,17,15,180,180,false,pen);break;
            case "play":if(getText().toString().contains("Ⅱ")){line(canvas,8,4,8,20);line(canvas,16,4,16,20);}else{Path triangle=new Path();triangle.moveTo(6,3);triangle.lineTo(21,12);triangle.lineTo(6,21);triangle.close();pen.setStyle(Paint.Style.FILL);canvas.drawPath(triangle,pen);}break;
            default:canvas.drawCircle(12,12,9,pen);
        }
        canvas.restore();
    }
}
