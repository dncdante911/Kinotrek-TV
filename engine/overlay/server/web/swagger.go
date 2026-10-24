package web

// Replaces TorrServer's Swagger UI: it adds 8 MB to every ABI and the app only uses the HTTP API.

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func swaggerHandler() gin.HandlerFunc {
	return func(c *gin.Context) { c.Status(http.StatusNotFound) }
}
