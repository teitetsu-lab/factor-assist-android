package jp.factor.overlay;

/**
 * Confirms that the page title is still the one OCR last read, from the shape of its brown text, so a
 * failed OCR on an unchanged title does not throw the frame away.
 *
 * It never names a page by itself: 再獲得因子1…9 differ only in the last digit, so only the current
 * page's shape is kept, a page switch always needs a successful OCR, and a shape is only kept after two
 * OCR reads agree on it.
 *
 * Each of the 64×8 cells holds the share of text pixels (0–255) rather than a bit: on the recorded S25
 * frame, ±5 pixel noise moves a cell by at most 39, while changing even half of the digit moves at least
 * two cells by more than 64. Any cell moving past CELL_TOLERANCE therefore means a different title.
 */
final class TitleMask {
    static final int COLUMNS=64, ROWS=8;
    /** Fewer cells with some text than this means no title is showing (e.g. mid swipe). */
    static final int MIN_TEXT_CELLS=12;
    static final int CELL_TOLERANCE=48;

    /** The title text box, from the device calibration (S25 defaults, or fitted by the user). */
    static int[] box(int width,int height){return Calibration.current.titleBox(width,height);}

    static boolean text(int c){
        int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
        return r>=80&&r<=170&&g>=35&&g<=110&&b<80&&r-b>50;
    }

    /** Text share per cell of the title box, or null when it holds too little text to be a title. */
    static byte[] of(int[] pixels,int width,int height) {
        int[] b=box(width,height);int bw=b[2]-b[0],bh=b[3]-b[1];if(bw<COLUMNS||bh<ROWS)return null;
        int[] hits=new int[COLUMNS*ROWS],total=new int[COLUMNS*ROWS];
        for(int y=b[1];y<b[3];y++){int row=(y-b[1])*ROWS/bh;
            for(int x=b[0];x<b[2];x++){int cell=row*COLUMNS+(x-b[0])*COLUMNS/bw;total[cell]++;if(text(pixels[y*width+x]))hits[cell]++;}}
        byte[] shares=new byte[COLUMNS*ROWS];int withText=0;
        for(int i=0;i<hits.length;i++){int share=hits[i]*255/total[i];shares[i]=(byte)share;if(share*100>=255*15)withText++;}
        return withText<MIN_TEXT_CELLS?null:shares;
    }
    /** Number of cells whose text share differs by more than CELL_TOLERANCE; 0 means the same title. */
    static int distance(byte[] a,byte[] c){int d=0;for(int i=0;i<a.length;i++)if(Math.abs((a[i]&255)-(c[i]&255))>CELL_TOLERANCE)d++;return d;}

    private String current;private byte[] currentMask;
    private String pendingLabel;private byte[] pendingMask;

    /** The current page if mask is its unchanged title, otherwise null (the title must be read by OCR). */
    String match(byte[] mask){return mask!=null&&currentMask!=null&&distance(mask,currentMask)==0?current:null;}
    /** Called with each OCR read; the shape becomes the current page once two consecutive reads agree. */
    void learn(String label,byte[] mask) {
        if(mask==null||label.isEmpty())return;
        if(label.equals(pendingLabel)&&pendingMask!=null&&distance(mask,pendingMask)==0){current=label;currentMask=mask;pendingLabel=null;pendingMask=null;}
        else{pendingLabel=label;pendingMask=mask;}
    }
    String current(){return current;}
}
