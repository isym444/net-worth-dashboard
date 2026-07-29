import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const key = authService.apiKey();
  if (!key) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { 'X-Api-Key': key } }));
};
