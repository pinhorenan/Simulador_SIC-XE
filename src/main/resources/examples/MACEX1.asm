COPYMAC MACRO
        LDA     SRC
        STA     DST
        MEND

MACEX1  START   0x000000
        COPYMAC
        RSUB
SRC     WORD    5
DST     RESW    1
        END     MACEX1
