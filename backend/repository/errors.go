package repository

import "errors"

// ErrConflict is returned by Update/Delete when the caller's base_version
// does not match the current server version. The caller is expected to
// refetch and surface the conflict to the user.
var ErrConflict = errors.New("version conflict")
