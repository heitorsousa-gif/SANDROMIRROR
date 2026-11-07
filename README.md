# SandroMirror

App Android para espelhar o celular na central multimídia **XM7706** (software V7.52BT-FK-TP), no lugar do Hi-Link. Leve (menos de 50 KB), sem anúncios e sem rastreamento.

## Baixar

Última versão: https://github.com/heitorsousa-gif/SANDROMIRROR/releases/latest

Depois de instalado, as próximas versões chegam pelo botão **⟳ Atualizar app** (na engrenagem do painel).

Se uma versão nova der problema, a versão estável 0.4 está em [`backup/`](backup/).

## Como usar

1. Conecte o celular na central com um cabo de dados.
2. O painel abre deitado e pede a gravação de tela: aceite.
3. Na primeira vez, libere "Sobrepor a outros apps" para ter o botão 🏠 flutuante e os apps deitados na central.

## O que tem

- Painel com botões grandes: Mapa, Falar destino (voz), Waze, Spotify, YouTube e Assistente.
- Mantém a tela acesa enquanto espelha.
- **Modo direção** no visual Windows 95 cyberpunk: suas fotos passando como papel de parede (em pé e deitadas, sem cortar), com pausar e fixar; player da música tocando com equalizador; próxima instrução do Maps. Precisa liberar "Acesso a notificações" uma vez.
- **Modo central / modo celular**: deitado para a central, ou em pé para o copiloto (é no modo celular que se escolhem as fotos e o tempo de troca).
- Economia de bateria: no modo central a tela do celular fica no brilho mínimo enquanto espelha.
- Fontes VT323 e Orbitron (licença SIL OFL, em `app/src/main/assets/fonts`).
- Toque na tela da central controla o celular.
- Sem permissão de telefone e sem "desligamento remoto" (o Hi-Link tem os dois).

## Protocolo da central (extraído do Hi-Link 1.8)

- USB no modo Android Open Accessory. A central se identifica como `AMirroring` / `com.mirror.hsn` / `Mirroring Suite`.
- Batimento: a central manda `00 00 00 00` a cada 500 ms e o celular devolve os mesmos 4 bytes.
- Mensagem: `[tamanho LE 4][comando 4 letras invertidas][seq 4][resposta 4][chave 4][campos...]`. Bytes 4..63 embaralhados com XOR pela chave, que fica em texto aberto nos bytes 16..19. Mensagens curtas completadas até 64 bytes.
- `SYNC`: central pede, celular responde `i32 2`.
- `STAR`: central pede `i16 largura, i16 altura, i32 bitrate, i32 ?`; celular responde `i16 larg, i16 alt, i32 bitrate, i16 tela maior, i16 tela menor, i32 0, i32 0` e começa a mandar vídeo.
- `VDAT`: `i32 horário ms, i32 rotação, i32 tamanho, H.264`. O primeiro leva SPS+PPS.
- `VCTL`: central pausa (`1`) ou retoma (`0`) o vídeo. `LIVE` e `SLCK`: manutenção e estado da tela.

O código do Hi-Link (protegido com 360 Jiagu) foi lido rodando o app num emulador e copiando o dex da memória (`tools/dump.sh`).

## Compilação

O GitHub Actions compila e publica uma versão nova a cada mudança na branch `main`.
O número da versão é `100 + número da compilação`. A chave `app/carlink-test.keystore` é de teste (senha `android`), para que cada versão instale por cima da anterior.
