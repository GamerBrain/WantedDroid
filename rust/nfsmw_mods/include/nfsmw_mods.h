#ifndef NFSMW_MODS_FFI_H
#define NFSMW_MODS_FFI_H

#ifdef __cplusplus
extern "C" {
#endif

/* Returns 1 if folder is a safe single mod folder name, 0 otherwise. */
int nfsmw_mods_is_safe_folder_name(const char* folder);
/* Returns 1 if path is a safe relative overlay path (no traversal/absolute), 0 otherwise. */
int nfsmw_mods_is_safe_relative_path(const char* path);

#ifdef __cplusplus
}
#endif

#endif /* NFSMW_MODS_FFI_H */
