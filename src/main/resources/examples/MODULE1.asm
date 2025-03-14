MODULE1  START   100
         EXTDEF  ALPHA               ; Exporta o símbolo ALPHA
         EXTREF  BETA, ADDALPHA, CLEARALPHA
         ; Importa BETA e ADDALPHA (definidos no MODULE2)
         ; e CLEARALPHA (definido no MODULE3)

ALPHA    WORD    0                   ; Uma variável global (pública)

; --- Trecho principal de código ---
MAIN     LDA     #5                  ; Carrega imediato 5 em A
         STA     ALPHA               ; ALPHA = 5
         JSUB    ADDALPHA            ; Chama sub-rotina do Module2: ALPHA = ALPHA + BETA
         JSUB    CLEARALPHA          ; Chama sub-rotina do Module3: ALPHA = 0
         RSUB                         ; Termina a execução deste módulo (CPU para)
         END     MODULE1
