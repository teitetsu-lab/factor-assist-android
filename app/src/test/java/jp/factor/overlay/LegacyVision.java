package jp.factor.overlay;

import java.util.*;

/** Verbatim copy of FactorVision.detect/crop before the allocation changes, kept as the equivalence reference. */
final class LegacyVision {
    static FactorVision.Layout detect(int[] pixels,int width,int height) {
        int w=(width+1)/2,h=(height+1)/2;
        boolean[] mask=new boolean[w*h];int[] queue=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)mask[y*w+x]=FactorVision.gold(pixels[(y*2)*width+x*2]);
        List<FactorVision.Blob> blobs=new ArrayList<>();List<Integer> areas=new ArrayList<>();
        for(int start=0;start<mask.length;start++) {
            if(!mask[start])continue;
            int read=0,end=1,minX=start%w,maxX=minX,minY=start/w,maxY=minY;queue[0]=start;mask[start]=false;
            while(read<end){int p=queue[read++],x=p%w,y=p/w;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                if(x>0&&mask[p-1]){mask[p-1]=false;queue[end++]=p-1;}if(x+1<w&&mask[p+1]){mask[p+1]=false;queue[end++]=p+1;}
                if(y>0&&mask[p-w]){mask[p-w]=false;queue[end++]=p-w;}if(y+1<h&&mask[p+w]){mask[p+w]=false;queue[end++]=p+w;}
            }
            blobs.add(new FactorVision.Blob((minY+maxY+1)/2.0,minX,end,maxX-minX+1,maxY-minY+1));if(end>2.5)areas.add(end);
        }
        if(areas.isEmpty())return null;double area=FactorVision.median(areas);
        blobs.removeIf(b->b.area()<area*.3||b.area()>area*4||b.height()/(double)b.width()<=.5||b.height()/(double)b.width()>=2.2);
        blobs.sort(Comparator.comparingDouble(FactorVision.Blob::y));if(blobs.size()<3)return null;
        List<List<FactorVision.Blob>> groups=new ArrayList<>();
        for(FactorVision.Blob b:blobs){if(groups.isEmpty()||b.y()-groups.get(groups.size()-1).get(groups.get(groups.size()-1).size()-1).y()>h*.012)groups.add(new ArrayList<>());groups.get(groups.size()-1).add(b);}
        List<Integer> ys=new ArrayList<>(),xs=new ArrayList<>(),diff=new ArrayList<>();
        for(var group:groups){List<Integer> centers=new ArrayList<>();int left=Integer.MAX_VALUE;for(FactorVision.Blob b:group){centers.add((int)(b.y()*2));left=Math.min(left,b.x()*2);}ys.add(FactorVision.median(centers));xs.add(left);}
        if(ys.size()<3)return null;for(int i=1;i<ys.size();i++)diff.add(ys.get(i)-ys.get(i-1));int pitch=FactorVision.median(diff);if(pitch<12)return null;
        int bestStart=0,bestSize=0;
        for(int i=0;i<ys.size();){int j=i;while(j+1<ys.size()&&Math.abs(ys.get(j+1)-ys.get(j)-pitch)<pitch*.35)j++;if(j-i+1>bestSize){bestStart=i;bestSize=j-i+1;}i=j+1;}
        if(bestSize<3)return null;
        return new FactorVision.Layout(pitch,FactorVision.median(xs.subList(bestStart,bestStart+bestSize)),new ArrayList<>(ys.subList(bestStart,bestStart+bestSize)));
    }

    static byte[] crop(int[] pixels,int width,int height,int left,int top,int right,int bottom,int outWidth,boolean debadge) {
        if(left<0||top<0||right>width||bottom>height||right<=left||bottom<=top)return null;
        int cw=right-left,ch=bottom-top;int[] local=new int[cw*ch];
        for(int y=0;y<ch;y++)System.arraycopy(pixels,(top+y)*width+left,local,y*cw,cw);
        if(debadge){int first=cw,last=-1;for(int x=0;x<cw;x++){int red=0;for(int y=0;y<ch;y++){int c=local[y*cw+x];if(((c>>16)&255)>235&&((c>>8)&255)<100&&(c&255)<150)red++;}if(red>2){first=Math.min(first,x);last=x;}}
            if(last-first>=2){int[] components=new int[3];for(int c=0;c<3;c++){List<Integer> values=new ArrayList<>();for(int y=0;y<ch;y++)for(int x=Math.max(0,cw-12);x<cw;x++)values.add((local[y*cw+x]>>(c*8))&255);components[c]=FactorVision.median(values);}int fill=components[0]|components[1]<<8|components[2]<<16;for(int y=0;y<ch;y++)for(int x=Math.max(0,first-3);x<Math.min(cw,last+4);x++)local[y*cw+x]=fill;}}
        byte[] output=new byte[outWidth*16*3];int[] horizontal=new int[ch*outWidth*3];double sx=cw/(double)outWidth,sy=ch/16.0,fx=Math.max(1,sx),fy=Math.max(1,sy);
        for(int y=0;y<ch;y++)for(int x=0;x<outWidth;x++){double center=(x+.5)*sx;int a=Math.max(0,(int)(center-fx+.5)),b=Math.min(cw,(int)(center+fx+.5));double sum=0;double[] rgb=new double[3];for(int k=a;k<b;k++){double weight=Math.max(0,1-Math.abs((k+.5-center)/fx));sum+=weight;int p=local[y*cw+k];for(int c=0;c<3;c++)rgb[c]+=weight*((p>>(8*c))&255);}for(int c=0;c<3;c++)horizontal[(y*outWidth+x)*3+c]=(int)Math.round(rgb[c]/sum);}
        for(int y=0;y<16;y++)for(int x=0;x<outWidth;x++){double center=(y+.5)*sy;int a=Math.max(0,(int)(center-fy+.5)),b=Math.min(ch,(int)(center+fy+.5));double sum=0;double[] rgb=new double[3];for(int k=a;k<b;k++){double weight=Math.max(0,1-Math.abs((k+.5-center)/fy));sum+=weight;for(int c=0;c<3;c++)rgb[c]+=weight*horizontal[(k*outWidth+x)*3+c];}for(int c=0;c<3;c++)output[(y*outWidth+x)*3+c]=(byte)Math.round(rgb[c]/sum);}
        return output;
    }
}
