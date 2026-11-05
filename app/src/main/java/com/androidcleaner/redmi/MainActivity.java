package com.androidcleaner.redmi;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    static final long MB = 1024L * 1024L;
    static final long LIMITE = 100L * MB;
    static final long ANTIGO = 180L * 86400000L;

    TextView status, resultado;
    volatile boolean parar = false;
    volatile boolean trabalhando = false;

    List<Item> ultimo = new ArrayList<>();
    Set<String> duplicados = new HashSet<>();

    static class Item {
        String caminho;
        long tamanho, data;

        Item(File f) {
            caminho = f.getAbsolutePath();
            tamanho = f.length();
            data = f.lastModified();
        }
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout tela = new LinearLayout(this);
        tela.setOrientation(LinearLayout.VERTICAL);
        tela.setPadding(20, 25, 20, 20);

        TextView titulo = new TextView(this);
        titulo.setText("ANDROID CLEANER 2.1");
        titulo.setTextSize(24);
        titulo.setTypeface(null, Typeface.BOLD);
        tela.addView(titulo);

        TextView info = new TextView(this);
        info.setText("Redmi Note 8 Pro | Scanner sem root\nNenhum arquivo sera excluido.");
        tela.addView(info);

        Button analisar = new Button(this);
        analisar.setText("ANALISAR ARMAZENAMENTO");
        tela.addView(analisar);

        Button cancelar = new Button(this);
        cancelar.setText("PARAR ANALISE");
        tela.addView(cancelar);

        Button exportar = new Button(this);
        exportar.setText("EXPORTAR RELATORIO CSV");
        tela.addView(exportar);

        status = new TextView(this);
        status.setText("Pronto para analisar.");
        status.setPadding(0, 15, 0, 15);
        tela.addView(status);

        ScrollView scroll = new ScrollView(this);
        resultado = new TextView(this);
        resultado.setText("O diagnostico aparecera aqui.");
        resultado.setTextSize(14);
        scroll.addView(resultado);
        tela.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(tela);

        analisar.setOnClickListener(v -> verificarPermissao());
        cancelar.setOnClickListener(v -> {
            parar = true;
            status.setText("Solicitando interrupcao...");
        });
        exportar.setOnClickListener(v -> exportar());
    }

    void verificarPermissao() {
        if (trabalhando) return;

        if (Build.VERSION.SDK_INT >= 30 &&
            !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            status.setText("Autorize o acesso e volte para iniciar a analise.");
            return;
        }

        if (Build.VERSION.SDK_INT < 30 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 5);
            return;
        }

        parar = false;
        trabalhando = true;
        status.setText("Iniciando...");
        new Thread(this::analisar).start();
    }

    void atualizar(String texto) {
        runOnUiThread(() -> status.setText(texto));
    }

    String tamanho(long n) {
        if (n >= 1024 * MB)
            return String.format(Locale.US, "%.2f GB", n / (1024.0 * MB));
        return String.format(Locale.US, "%.1f MB", n / (double) MB);
    }

    String hash(String path) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            try (InputStream in = new BufferedInputStream(new FileInputStream(path))) {
                int n;
                while ((n = in.read(b)) != -1) {
                    if (parar) return null;
                    d.update(b, 0, n);
                }
            }
            byte[] digest = d.digest();
            StringBuilder s = new StringBuilder();
            for (byte x : digest)
                s.append(String.format(Locale.US, "%02x", x & 255));
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    void analisar() {
        try {
            File raiz = Environment.getExternalStorageDirectory();
            String base = raiz.getAbsolutePath();
            ArrayDeque<File> fila = new ArrayDeque<>();
            List<Item> arquivos = new ArrayList<>();
            Map<String, Long> pastas = new HashMap<>();
            fila.push(raiz);

            int bloqueadas = 0;
            long totalArquivos = 0;
            long bytes = 0;
            long corte = System.currentTimeMillis() - ANTIGO;

            while (!fila.isEmpty() && !parar) {
                File atual = fila.pop();
                if (Files.isSymbolicLink(atual.toPath())) continue;

                if (atual.isDirectory()) {
                    File[] filhos;
                    try {
                        filhos = atual.listFiles();
                    } catch (Exception e) {
                        filhos = null;
                    }
                    if (filhos == null) {
                        bloqueadas++;
                        continue;
                    }
                    for (File f : filhos) fila.push(f);
                } else if (atual.isFile() && atual.canRead()) {
                    Item item = new Item(atual);
                    arquivos.add(item);
                    totalArquivos++;
                    bytes += item.tamanho;

                    String relativo = item.caminho.substring(base.length());
                    if (relativo.startsWith("/"))
                        relativo = relativo.substring(1);
                    String pasta = relativo.split("/")[0];
                    pastas.put(pasta,
                        pastas.getOrDefault(pasta, 0L) + item.tamanho);

                    if (totalArquivos % 500 == 0) {
                        final long n = totalArquivos;
                        atualizar("Lendo arquivos: " + n);
                    }
                }
            }

            if (parar) {
                atualizar("Analise interrompida.");
                return;
            }

            atualizar("Verificando duplicados exatos...");

            Map<Long, List<Item>> porTamanho = new HashMap<>();
            for (Item i : arquivos) {
                if (i.tamanho == 0) continue;
                porTamanho.computeIfAbsent(
                    i.tamanho, k -> new ArrayList<>()).add(i);
            }

            Set<String> dup = new HashSet<>();
            StringBuilder detalhes = new StringBuilder();
            long economia = 0;
            int grupos = 0;

            for (List<Item> candidatos : porTamanho.values()) {
                if (parar) break;
                if (candidatos.size() < 2) continue;

                Map<String, List<Item>> iguais = new HashMap<>();

                for (Item i : candidatos) {
                    if (parar) break;
                    String h = hash(i.caminho);
                    if (h != null)
                        iguais.computeIfAbsent(
                            h, k -> new ArrayList<>()).add(i);
                }

                for (List<Item> grupo : iguais.values()) {
                    if (grupo.size() < 2) continue;
                    grupos++;
                    economia += (grupo.size() - 1L) * grupo.get(0).tamanho;

                    for (Item i : grupo) dup.add(i.caminho);

                    if (grupos <= 10) {
                        detalhes.append("\nGrupo ").append(grupos)
                            .append(" (").append(tamanho(grupo.get(0).tamanho))
                            .append(")\n");
                        for (Item i : grupo)
                            detalhes.append(i.caminho).append("\n");
                    }
                }
            }

            if (parar) {
                atualizar("Analise interrompida.");
                return;
            }

            arquivos.sort((a,b) -> Long.compare(b.tamanho, a.tamanho));

            StatFs st = new StatFs(base);
            StringBuilder s = new StringBuilder();

            s.append("DIAGNOSTICO DO ARMAZENAMENTO\n\n");
            s.append("Capacidade: ").append(tamanho(st.getTotalBytes())).append("\n");
            s.append("Espaco livre: ").append(tamanho(st.getAvailableBytes())).append("\n");
            s.append("Arquivos acessiveis: ").append(totalArquivos).append("\n");
            s.append("Volume analisado: ").append(tamanho(bytes)).append("\n");
            s.append("Pastas inacessiveis: ").append(bloqueadas).append("\n");

            s.append("\nDUPLICADOS EXATOS\n");
            s.append("Grupos: ").append(grupos).append("\n");
            s.append("Espaco potencialmente recuperavel: ")
                .append(tamanho(economia)).append("\n");
            s.append(detalhes);

            List<Map.Entry<String,Long>> ranking =
                new ArrayList<>(pastas.entrySet());
            ranking.sort((a,b) -> Long.compare(b.getValue(), a.getValue()));

            s.append("\nPASTAS COM MAIOR CONSUMO\n");
            for (int j=0; j<Math.min(15,ranking.size()); j++) {
                Map.Entry<String,Long> e = ranking.get(j);
                s.append(e.getKey()).append(": ")
                    .append(tamanho(e.getValue())).append("\n");
            }

            s.append("\n20 MAIORES ARQUIVOS\n");
            for (int j=0; j<Math.min(20,arquivos.size()); j++) {
                Item i = arquivos.get(j);
                s.append(tamanho(i.tamanho)).append(" - ")
                    .append(i.caminho).append("\n");
            }

            s.append("\nDOWNLOADS ANTIGOS (>180 DIAS)\n");
            int antigos = 0;
            for (Item i : arquivos) {
                String p = i.caminho.toLowerCase(Locale.ROOT);
                if (i.data > 0 && i.data < corte &&
                    (p.contains("/download/") || p.contains("/downloads/"))) {
                    antigos++;
                    if (antigos <= 15)
                        s.append(tamanho(i.tamanho)).append(" - ")
                            .append(i.caminho).append("\n");
                }
            }
            s.append("Total encontrado: ").append(antigos).append("\n");
            s.append("\nNOTA: Pastas privadas de aplicativos nao foram analisadas.");

            ultimo = arquivos;
            duplicados = dup;
            String texto = s.toString();

            runOnUiThread(() -> {
                resultado.setText(texto);
                status.setText("Analise concluida. Nenhum arquivo foi apagado.");
            });

        } catch (Exception e) {
            atualizar("Erro na analise: " + e.getMessage());
        } finally {
            trabalhando = false;
        }
    }

    void exportar() {
        if (trabalhando || ultimo.isEmpty()) {
            Toast.makeText(this, "Conclua uma analise primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "AndroidCleaner_relatorio.csv");
        startActivityForResult(i, 101);
    }

    String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != 101 || result != RESULT_OK || data == null) return;

        Uri destino = data.getData();
        List<Item> copia = new ArrayList<>(ultimo);
        Set<String> dup = new HashSet<>(duplicados);

        new Thread(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(destino);
                 OutputStreamWriter w = new OutputStreamWriter(os, "UTF-8")) {
                w.write("\uFEFFtipo,caminho,bytes,modificado\n");
                long corte = System.currentTimeMillis() - ANTIGO;

                for (Item i : copia) {
                    String tipo = "NORMAL";
                    if (dup.contains(i.caminho)) tipo = "DUPLICADO_EXATO";
                    else if (i.tamanho >= LIMITE) tipo = "GRANDE";
                    else if (i.data > 0 && i.data < corte &&
                        i.caminho.toLowerCase(Locale.ROOT).contains("/download/"))
                        tipo = "DOWNLOAD_ANTIGO";

                    w.write(csv(tipo) + "," + csv(i.caminho) + "," +
                        i.tamanho + "," + i.data + "\n");
                }
                atualizar("Relatorio CSV exportado.");
            } catch (Exception e) {
                atualizar("Falha ao exportar: " + e.getMessage());
            }
        }).start();
    }
}