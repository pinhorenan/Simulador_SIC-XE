MODULE3  START 300
         EXTREF ALPHA                ; Para acessar ALPHA
         EXTDEF CLEARALPHA           ; Exporta a sub-rotina CLEARALPHA

CLEARALPHA LDA    #0                 ; Carrega 0
           STA    ALPHA              ; ALPHA = 0
           RSUB
           END   MODULE3
