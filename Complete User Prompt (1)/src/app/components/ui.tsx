import React from "react";
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function Card({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("bg-surface text-on-surface rounded-[24px] p-4 shadow-sm border border-outline-variant/30", className)}
      {...props}
    >
      {children}
    </div>
  );
}

export function Button({ 
  className, 
  variant = "filled", 
  size = "md",
  children, 
  ...props 
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { 
  variant?: "filled" | "tonal" | "outlined" | "text",
  size?: "sm" | "md" | "lg"
}) {
  const baseStyles = "inline-flex items-center justify-center rounded-full font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:opacity-50 disabled:pointer-events-none";
  
  const variants = {
    filled: "bg-primary text-on-primary hover:bg-primary/90",
    tonal: "bg-secondary-container text-on-secondary-container hover:bg-secondary-container/80",
    outlined: "border border-outline text-primary hover:bg-primary/5",
    text: "text-primary hover:bg-primary/5",
  };
  
  const sizes = {
    sm: "h-9 px-4 text-sm",
    md: "h-12 px-6 text-base",
    lg: "h-14 px-8 text-lg",
  };

  return (
    <button className={cn(baseStyles, variants[variant], sizes[size], className)} {...props}>
      {children}
    </button>
  );
}

export function Switch({ checked, onCheckedChange }: { checked: boolean, onCheckedChange: (c: boolean) => void }) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      onClick={() => onCheckedChange(!checked)}
      className={cn(
        "relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2",
        checked ? "bg-primary" : "bg-surface-variant border-outline"
      )}
    >
      <span
        className={cn(
          "pointer-events-none inline-block h-6 w-6 transform rounded-full bg-surface shadow-lg ring-0 transition duration-200 ease-in-out",
          checked ? "translate-x-6 scale-100 bg-on-primary" : "translate-x-0 bg-outline"
        )}
      />
    </button>
  );
}

export function Pill({ children, variant = "default" }: { children: React.ReactNode, variant?: "default" | "success" | "warning" | "error" }) {
  const variants = {
    default: "bg-surface-variant text-on-surface-variant",
    success: "bg-teal-100 text-teal-900 dark:bg-teal-900/30 dark:text-teal-200",
    warning: "bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-200",
    error: "bg-error-container text-on-error-container",
  };
  return (
    <span className={cn("px-3 py-1 rounded-full text-xs font-medium inline-flex items-center gap-1.5", variants[variant])}>
      {children}
    </span>
  );
}
