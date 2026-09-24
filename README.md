# post-editor

A simple post editor developed in Java for blogs and pages on GitHub Pages

Editor desktop (Java Swing) para blogs Jekyll hospedados no **GitHub Pages**.
Você seleciona a pasta do repositório do blog e o editor cria, edita e exclui
posts — e cada operação já faz **commit e push** automaticamente.

## Funcionalidades

- **Selecionar o repositório** do blog (pasta local clonada); a escolha fica salva.
- **Novo post**: gera `_posts/AAAA-MM-DD-titulo-do-post.md` com o front matter
  (`layout`, `title`, `date`, `categories`, `tags`).
- **Rascunhos** (`Ctrl+Shift+S`): salvam o post só no computador, na pasta
  `_drafts` do Jekyll, sem commit nem push — o site não é alterado. Ao clicar em
  **Salvar e publicar**, o rascunho vai para `_posts` (com suas imagens) e é
  enviado ao GitHub. Em um post já publicado, **Salvar rascunho** grava as
  alterações localmente sem publicar; a lista marca esses posts como
  "não publicado" até a próxima publicação.
- **Agendar publicação** (botão **Agendar...**): escolha data e hora e o post
  é publicado automaticamente (commit + push) quando chegar o horário. Até lá
  ele fica como rascunho, com o agendamento gravado no próprio arquivo
  (`publish_at` no front matter). Na publicação, a `date` do post passa a ser a
  data agendada.
  - Ao fechar a janela, o Post Editor **continua rodando na bandeja do sistema**
    (perto do relógio). Pelo ícone é possível reabrir a janela ou sair.
  - Se o computador/aplicativo estiver desligado no horário, o post é publicado
    assim que o Post Editor for aberto de novo.
  - Se o envio falhar (ex.: sem internet), ele tenta de novo a cada 5 minutos.
  - Posts já publicados não podem ser agendados, só novos posts e rascunhos.
- **Editar posts antigos**: a lista lateral mostra todos os posts (com busca).
  Campos extras do front matter (ex.: `image`, `permalink`) são preservados.
- **Excluir posts**: remove o arquivo e a pasta de imagens do post.
- **Commit + push automáticos** em cada publicação/exclusão
  (`git add` → `git commit` apenas dos arquivos do post → `git push`).
  Se o remoto tiver alterações novas, o editor faz `pull --rebase` e tenta de novo.
- **Editor Markdown com pré-visualização ao vivo** e barra de ferramentas:
  - títulos (H1–H3), **negrito**, *itálico*, ~~tachado~~, `código inline`
  - links e **imagens** — do computador (copiadas para `assets/images/<post>/`
    e publicadas junto com o post) ou por URL
  - **tabelas** (gerador de tabela ou conversão de texto copiado de planilha/CSV)
  - **blocos de código** com linguagem (destaque de sintaxe no site)
  - citações, listas com marcadores, numeradas e de tarefas
  - linha horizontal, notas de rodapé e seções recolhíveis (`<details>`)
  - atalhos: `Ctrl+S` publicar, `Ctrl+Shift+S` salvar rascunho, `Ctrl+N` novo post, `Ctrl+B`, `Ctrl+I`, `Ctrl+K`,
    `Ctrl+Z`/`Ctrl+Y`
- Log com a saída dos comandos git executados.

## Requisitos

- Java **8 ou superior** (JDK para compilar; JRE basta para executar o `.jar`).
- **Git** instalado e no `PATH`, com credenciais configuradas para dar push no
  repositório (SSH ou Git Credential Manager). Teste com `git push` no terminal.
- O repositório do blog **clonado** localmente (`git clone ...`).

## Compilar e executar

Com Maven:

```bash
mvn package
java -jar target/post-editor.jar
```

Sem Maven (apenas JDK):

```bash
./build.sh          # Linux/macOS
build.bat           # Windows
java -jar post-editor.jar
```

Para iniciar direto na bandeja (por exemplo, junto com o Windows), use:

```bash
java -jar post-editor.jar --bandeja
```

No Windows, crie um atalho com esse comando (usando `javaw` no lugar de `java`
para não abrir um console) e coloque-o na pasta de inicialização
(`Win+R` → `shell:startup`).

## Como usar

1. Clique em **Abrir repositório...** e selecione a pasta do blog.
2. Clique em **Novo post**, preencha título, categorias/tags e escreva em Markdown.
3. Use a barra de ferramentas para inserir imagens, tabelas, código etc.
4. Para continuar depois, clique em **Salvar rascunho** — nada é enviado ao site.
5. Para publicar mais tarde automaticamente, clique em **Agendar...** e escolha
   a data e hora. Pode fechar a janela: o app fica na bandeja e publica no horário.
6. Quando estiver pronto, clique em **Salvar e publicar** (`Ctrl+S`). O GitHub Pages atualiza o site em
   alguns instantes.
7. Para editar, selecione um post na lista, altere e publique novamente.
   Para remover, selecione e clique em **Excluir post**.

As imagens locais são referenciadas como
`{{ site.baseurl }}/assets/images/<post>/<arquivo>`, o que funciona tanto em
sites de usuário (`usuario.github.io`) quanto de projeto (`usuario.github.io/repo`).

## Configuração

As preferências ficam em `~/.post-editor.properties`. Além do repositório,
é possível ajustar:

```properties
posts.dir=_posts
drafts.dir=_drafts
images.dir=assets/images
default.layout=post
```

## Licença

GPL-3.0 — veja [LICENSE](LICENSE).
