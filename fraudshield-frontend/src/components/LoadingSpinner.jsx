export default function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center h-32">
      <div className="w-10 h-10 border-4 border-dark-border border-t-indigo-500 rounded-full animate-spin" />
    </div>
  );
}
