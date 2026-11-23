#pragma once
#include <cstdio>
#include <dirent.h>
#include <sys/stat.h>
#include <string>
#include <stdexcept>
#include <filesystem>
#include <android/log.h>

inline std::string nfsmwResolveGameExe(const std::string& root) {
    namespace fs = std::filesystem;
    std::error_code ec;
    for (const char* name : {"Speed.exe", "speed.exe", "SPEED.EXE"}) {
        fs::path p = fs::path(root) / name;
        if (fs::is_regular_file(p, ec)) return p.string();
    }
    return (fs::path(root) / "Speed.exe").string();
}

inline void checkGameData(const std::string& root) {
    const std::string exePath = nfsmwResolveGameExe(root);
    FILE* exe = std::fopen(exePath.c_str(), "rb");
    if (!exe) throw std::runtime_error("No se puede leer " + exePath + ". Verifica que la carpeta nfsmw esté en la memoria interna y tenga permisos de archivos.");
    unsigned char magic[2]{};
    const size_t read = std::fread(magic, 1, 2, exe);
    std::fclose(exe);
    struct stat info{};
    if (read != 2 || magic[0] != 'M' || magic[1] != 'Z' ||
        stat(exePath.c_str(), &info) || info.st_size != 6029312)  // speed.exe, SHA-256 80774c2e...253c
        throw std::runtime_error(exePath + " no coincide con la imagen esperada de Most Wanted (tamaño/cabecera).");
    // Most Wanted's data folders (the shared storage is case-insensitive).
    for (const char* name : {"CARS", "FRONTEND", "GLOBAL", "LANGUAGES", "MOVIES", "NIS", "SOUND", "TRACKS"}) {
        const auto path = root + "/" + name;
        DIR* dir = opendir(path.c_str());
        if (!dir) throw std::runtime_error("No se puede leer la carpeta del juego: " + path);
        unsigned files = 0;
        while (auto* entry = readdir(dir)) if (entry->d_name[0] != '.') ++files;
        closedir(dir);
        if (!files) throw std::runtime_error("Carpeta del juego vacía: " + path);
        __android_log_print(ANDROID_LOG_INFO, "NFSMW", "Data directory %s: %u entries", name, files);
    }
    __android_log_print(ANDROID_LOG_INFO, "NFSMW", "Game data readable at %s; executable header/size OK (%s)", root.c_str(), exePath.c_str());
}
